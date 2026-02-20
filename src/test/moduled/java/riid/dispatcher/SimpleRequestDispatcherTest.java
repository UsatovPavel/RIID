package riid.dispatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheEntry;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.CachePayload;
import riid.cache.oci.ImageDigest;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.TagList;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.p2p.P2PExecutor;

class SimpleRequestDispatcherTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String MEDIA_LAYER = "application/vnd.oci.image.layer.v1.tar";
    private static final String REPO = "repo";
    private static final String TAG = "tag";

    @Test
    void returnsCacheHit() {
        try (RecordingRegistryClient registry = new RecordingRegistryClient()) {
            RecordingCacheAdapter cache = new RecordingCacheAdapter();
            P2PExecutor p2p = new P2PExecutor.NoOp();
            HostFilesystem fs = new NioHostFilesystem();
            cache.hasEntry = true;
            cache.entry = new CacheEntry(ImageDigest.parse(DIGEST), 10, CacheMediaType.OCI_LAYER, "/tmp/cached");

            SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(registry, cache, p2p, fs);
            FetchResult result = dispatcher.fetchImage(new ImageRef(REPO, TAG, null));

            assertEquals(Path.of("/tmp/cached"), result.path());
            assertEquals(1, registry.manifestCalls);
            assertEquals(0, registry.blobCalls);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void returnsP2PWhenCacheMiss() {
        try (RecordingRegistryClient registry = new RecordingRegistryClient()) {
            RecordingCacheAdapter cache = new RecordingCacheAdapter();
            RecordingP2PExecutor p2p = new RecordingP2PExecutor();
            HostFilesystem fs = new NioHostFilesystem();
            p2p.fetchResult = Optional.of(Path.of("/tmp/p2p-layer"));

            SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(registry, cache, p2p, fs);
            FetchResult result = dispatcher.fetchImage(new ImageRef(REPO, TAG, null));

            assertEquals(Path.of("/tmp/p2p-layer"), result.path());
            assertEquals(1, registry.manifestCalls);
            assertEquals(0, registry.blobCalls);
            assertTrue(p2p.fetchCalled, "p2p fetch should be attempted");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void downloadsFromRegistryAndPublishes() throws IOException {
        try (RecordingRegistryClient registry = new RecordingRegistryClient()) {
            HostFilesystem fs = new NioHostFilesystem();
            Path cachedPath = TestPaths.tempFile(fs, "cache-", ".bin");
            fs.writeString(cachedPath, "cached");
            RecordingCacheAdapter cache = new RecordingCacheAdapter(cachedPath);
            RecordingP2PExecutor p2p = new RecordingP2PExecutor();

            SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(
                    registry, cache, p2p, new DispatcherConfig(1), fs);
            FetchResult result = dispatcher.fetchImage(new ImageRef(REPO, TAG, null));

            assertEquals(cachedPath, result.path());
            assertTrue(fs.exists(result.path()), "downloaded layer should exist");
            assertTrue(fs.size(result.path()) > 0, "downloaded layer should not be empty");
            assertEquals(1, registry.blobCalls);
            assertTrue(cache.putCalled, "cache should be populated after registry download");
            assertTrue(p2p.publishCalled, "p2p should be notified after registry download");
        }
    }

    @Test
    void deletesTempAfterCacheWrite() throws IOException {
        TrackingHostFilesystem trackingFs = new TrackingHostFilesystem();
        try (RecordingRegistryClient registry = new RecordingRegistryClient()) {
            RecordingP2PExecutor p2p = new RecordingP2PExecutor();
            Path cachedPath = TestPaths.tempFile(trackingFs, "cache-", ".bin");
            trackingFs.writeString(cachedPath, "cached");
            RecordingCacheAdapter cache = new RecordingCacheAdapter(cachedPath);

            SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(
                    registry, cache, p2p, new DispatcherConfig(1), trackingFs);
            FetchResult result = dispatcher.fetchImage(new ImageRef(REPO, TAG, null));

            assertEquals(cachedPath, result.path());
            assertTrue(cache.putCalled, "cache should be populated after registry download");
            assertTrue(p2p.publishCalled, "p2p should be notified after registry download");
            assertNotNull(trackingFs.lastTemp.get(), "temp file should be created");
            assertFalse(trackingFs.exists(trackingFs.lastTemp.get()),
                    "temp file should be deleted after cache write");
        }
    }

    /**
    * Minimal in-memory registry stub that returns a manifest with one layer.
    */
    private static final class RecordingRegistryClient implements RegistryClient {
        int manifestCalls;
        int blobCalls;

        @Override
        public ManifestResult fetchManifest(String repository, String reference) {
            manifestCalls++;
            Descriptor layer = new Descriptor(MEDIA_LAYER, DIGEST, 10);
            Manifest manifest = new Manifest(2, "application/vnd.oci.image.manifest.v1+json",
                    new Descriptor("application/json", DIGEST, 1), List.of(layer));
            return new ManifestResult(DIGEST, manifest.mediaType(), 42, manifest);
        }

        @Override
        public BlobResult fetchConfig(String repository, Manifest manifest, File target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlobResult fetchBlob(BlobRequest request, File target) {
            blobCalls++;
            if (target == null) {
                return new BlobResult(request.digest(), 0, request.mediaType(), "");
            }
            try {
                java.nio.file.Files.writeString(target.toPath(), "data");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            long size = target.length();
            return new BlobResult(request.digest(), size, request.mediaType(), target.getAbsolutePath());
        }

        @Override
        public Optional<Long> headBlob(String repository, String digest) {
            return Optional.empty();
        }

        @Override
        public TagList listTags(String repository, Integer n, String last) {
            return new TagList(repository, List.of());
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }

    private static final class RecordingCacheAdapter implements CacheAdapter {
        boolean hasEntry;
        CacheEntry entry;
        boolean putCalled;
        private final Path resolvedPath;

        private RecordingCacheAdapter() {
            this.resolvedPath = null;
        }

        private RecordingCacheAdapter(Path resolvedPath) {
            this.resolvedPath = resolvedPath;
        }

        @Override
        public boolean has(ImageDigest digest) {
            return hasEntry;
        }

        @Override
        public Optional<CacheEntry> get(ImageDigest digest) {
            return Optional.ofNullable(entry);
        }

        @Override
        public Optional<Path> resolve(String key) {
            if (resolvedPath != null) {
                return Optional.of(resolvedPath);
            }
            return key == null ? Optional.empty() : Optional.of(Path.of(key));
        }

        @Override
        public CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) {
            putCalled = true;
            long size;
            try {
                size = payload.sizeBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (resolvedPath != null) {
                return new CacheEntry(digest, size, mediaType, resolvedPath.toString());
            }
            return new CacheEntry(digest, size, mediaType, "/tmp/cache/" + digest.hex());
        }
    }

    private static final class RecordingP2PExecutor implements P2PExecutor {
        boolean fetchCalled;
        boolean publishCalled;
        Optional<Path> fetchResult = Optional.empty();

        @Override
        public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType) {
            fetchCalled = true;
            return fetchResult;
        }

        @Override
        public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
            publishCalled = true;
        }
    }

    private static final class TrackingHostFilesystem implements HostFilesystem {
        private final HostFilesystem delegate = new NioHostFilesystem();
        private final AtomicReference<Path> lastTemp = new AtomicReference<>();

        @Override
        public Path createFile(Path path) throws IOException {
            java.util.Objects.requireNonNull(path, "path");
            Path fileName = path.getFileName();
            String name = fileName != null ? fileName.toString() : "";
            if (name.startsWith("layer-") && name.endsWith(".bin")) {
                lastTemp.set(path);
            }
            return delegate.createFile(path);
        }

        @Override
        public Path createDirectory(Path dir) throws IOException {
            return delegate.createDirectory(dir);
        }

        @Override
        public Path copy(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
            return delegate.copy(source, target, options);
        }

        @Override
        public Path write(Path path, byte[] bytes, java.nio.file.OpenOption... options) throws IOException {
            return delegate.write(path, bytes, options);
        }

        @Override
        public Path writeString(Path path, String content, java.nio.file.OpenOption... options) throws IOException {
            return delegate.writeString(path, content, options);
        }

        @Override
        public java.io.InputStream newInputStream(Path path) throws IOException {
            return delegate.newInputStream(path);
        }

        @Override
        public java.io.OutputStream newOutputStream(Path path) throws IOException {
            return delegate.newOutputStream(path);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public boolean isRegularFile(Path path) {
            return delegate.isRegularFile(path);
        }

        @Override
        public long size(Path path) throws IOException {
            return delegate.size(path);
        }

        @Override
        public String probeContentType(Path path) throws IOException {
            return delegate.probeContentType(path);
        }

        @Override
        public java.util.stream.Stream<Path> walk(Path root) throws IOException {
            return delegate.walk(root);
        }

        @Override
        public Path atomicMove(Path source, Path target) throws IOException {
            return delegate.atomicMove(source, target);
        }
    }
}


