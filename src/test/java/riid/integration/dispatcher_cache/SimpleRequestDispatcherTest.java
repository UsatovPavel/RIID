package riid.integration.dispatcher_cache;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheEntry;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.CachePayload;
import riid.cache.oci.ImageDigest;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.TagList;
import riid.dispatcher.DispatcherConfig;
import riid.dispatcher.FetchResult;
import riid.dispatcher.ImageRef;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.p2p.P2PExecutor;

class SimpleRequestDispatcherTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String MEDIA_LAYER = "application/vnd.oci.image.layer.v1.tar";

    private RecordingRegistryClient registry;
    private RecordingCacheAdapter cache;
    private RecordingP2PExecutor p2p;
    private HostFilesystem fs;

    @BeforeEach
    void setUp() {
        registry = new RecordingRegistryClient();
        cache = new RecordingCacheAdapter();
        p2p = new RecordingP2PExecutor();
        fs = new NioHostFilesystem();
    }

    @AfterEach
    void tearDown() throws IOException {
        registry.close();
    }

    @Test
    void returnsCacheHit() {
        cache.hasEntry = true;
        cache.entry = new CacheEntry(ImageDigest.parse(DIGEST), 10, CacheMediaType.OCI_LAYER, "/tmp/cached");

        SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(registry, cache, p2p, fs);
        FetchResult result = dispatcher.fetchImage(new ImageRef("repo", "tag", null));

        assertEquals(Path.of("/tmp/cached"), result.path());
        assertEquals(1, registry.manifestCalls);
        assertEquals(0, registry.blobCalls);
        assertFalse(p2p.fetchCalled, "p2p should not be used on cache hit");
    }

    @Test
    void returnsP2PWhenCacheMiss() {
        p2p.fetchResult = Optional.of(Path.of("/tmp/p2p-layer"));

        SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(registry, cache, p2p, fs);
        FetchResult result = dispatcher.fetchImage(new ImageRef("repo", "tag", null));

        assertEquals(Path.of("/tmp/p2p-layer"), result.path());
        assertEquals(1, registry.manifestCalls);
        assertEquals(0, registry.blobCalls);
        assertTrue(p2p.fetchCalled, "p2p fetch should be attempted");
    }

    @Test
    void downloadsFromRegistryAndPublishes() throws IOException {
        File tmp = TestPaths.tempFile(fs, "blob-", ".bin").toFile();
        fs.writeString(tmp.toPath(), "data");
        registry.blobResult = new BlobResult(DIGEST, tmp.length(), MEDIA_LAYER, tmp.getAbsolutePath());

        SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(
                registry, cache, p2p, new DispatcherConfig(1), fs);
        FetchResult result = dispatcher.fetchImage(new ImageRef("repo", "tag", null));

        assertEquals(tmp.toPath(), result.path());
        assertEquals(1, registry.blobCalls);
        assertTrue(cache.putCalled, "cache should be populated after registry download");
        assertTrue(p2p.publishCalled, "p2p should be notified after registry download");
    }

    /**
    * Minimal in-memory registry stub that returns a manifest with one layer.
    */
    private static final class RecordingRegistryClient implements RegistryClient {
        int manifestCalls;
        int blobCalls;
        BlobResult blobResult;

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
            return blobResult != null
                    ? blobResult
                    : new BlobResult(request.digest(), 0, request.mediaType(), target.getAbsolutePath());
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
            return new CacheEntry(digest, size, mediaType, "/tmp/cache/" + digest.hex());
        }
    }

    private static final class RecordingP2PExecutor implements P2PExecutor {
        boolean fetchCalled;
        boolean publishCalled;
        Optional<Path> fetchResult = Optional.empty();

        @Override
        public Optional<Path> fetch(ImageDigest digest, long size, CacheMediaType mediaType) {
            fetchCalled = true;
            return fetchResult;
        }

        @Override
        public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
            publishCalled = true;
        }
    }
}


