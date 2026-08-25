package riid.app.ociarchive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import riid.app.core.model.ImageId;
import riid.cache.oci.ImageDigest;
import riid.client.api.ManifestResult;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.MediaType;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.dispatcher.model.RepositoryName;

/**
 * Prefix import (AGENT-90): {@code streamLayers} must hand every layer over in
 * manifest order as soon as that layer is on disk, without waiting for the rest
 * of the image.
 */
@Tag("filesystem")
class OciArchiveBuilderStreamLayersTest {

    private static final String CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json";
    private static final String LAYER_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip";
    private static final String MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
    private static final String NOT_USED = "Not used";
    private static final String SHA256 = "sha256:";
    private static final String CONFIG_DIGEST = SHA256 + "b".repeat(64);
    private static final List<String> LAYER_DIGESTS = List.of(SHA256 + "1".repeat(64), SHA256 + "2".repeat(64),
            SHA256 + "3".repeat(64));

    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    @Timeout(60)
    void deliversLayersInManifestOrderOnceDownloaded() throws Exception {
        BlobSource blobs = new BlobSource(fs);
        OciArchiveBuilder builder = new OciArchiveBuilder(blobs, fs, TestPaths.DEFAULT_BASE_DIR);
        List<String> delivered = new ArrayList<>();

        builder.streamLayers(imageId(), manifestResult(), layersOnly((layer, blobPath) -> {
            delivered.add(layer.digest());
            assertTrue(Files.isRegularFile(blobPath), "layer must already be on disk when handed over: " + blobPath);
            assertEquals(blobs.contentOf(layer.digest()), Files.readString(blobPath),
                    "sink must get this layer's blob, not another one");
        }));

        assertEquals(LAYER_DIGESTS, delivered, "layers must arrive in manifest order");
    }

    /**
     * While the consumer blocks on the first (slow) layer, the rest keep
     * downloading in parallel. The first layer's fetch is released only once the
     * tail is down, so an implementation that fetched in manifest order - or waited
     * for all downloads before importing anything - would deadlock here.
     */
    @Test
    @Timeout(60)
    void slowFirstLayerDoesNotStallDownloadsOfTheRest() throws Exception {
        CountDownLatch tailDownloaded = new CountDownLatch(LAYER_DIGESTS.size() - 1);
        BlobSource blobs = new BlobSource(fs);
        blobs.beforeFetch(LAYER_DIGESTS.getFirst(), () -> awaitOrFail(tailDownloaded));
        for (String digest : LAYER_DIGESTS.subList(1, LAYER_DIGESTS.size())) {
            blobs.afterFetch(digest, tailDownloaded::countDown);
        }
        OciArchiveBuilder builder = new OciArchiveBuilder(blobs, fs, TestPaths.DEFAULT_BASE_DIR);

        AtomicInteger tailDownloadedWhenFirstImported = new AtomicInteger(-1);
        builder.streamLayers(imageId(), manifestResult(), layersOnly((layer, blobPath) -> {
            if (layer.digest().equals(LAYER_DIGESTS.getFirst())) {
                tailDownloadedWhenFirstImported.set((int) tailDownloaded.getCount());
            }
        }));

        assertEquals(0, tailDownloadedWhenFirstImported.get(),
                "every other layer must already be downloaded while the consumer waits for the first one");
    }

    /**
     * The mirror case: layer 0 must be imported before the tail has finished
     * downloading - otherwise there is no overlap, just the old barrier.
     */
    @Test
    @Timeout(60)
    void importsAvailablePrefixBeforeTheLastLayerArrives() throws Exception {
        CountDownLatch prefixImported = new CountDownLatch(2);
        BlobSource blobs = new BlobSource(fs);
        blobs.beforeFetch(LAYER_DIGESTS.getLast(), () -> awaitOrFail(prefixImported));
        OciArchiveBuilder builder = new OciArchiveBuilder(blobs, fs, TestPaths.DEFAULT_BASE_DIR);

        builder.streamLayers(imageId(), manifestResult(), layersOnly((layer, blobPath) -> {
            if (!layer.digest().equals(LAYER_DIGESTS.getLast())) {
                prefixImported.countDown();
            }
        }));

        assertEquals(0, prefixImported.getCount(), "the first two layers must be imported while the last downloads");
    }

    @Test
    @Timeout(60)
    void failedDownloadFailsTheStreamInsteadOfHanging() throws Exception {
        BlobSource blobs = new BlobSource(fs);
        blobs.beforeFetch(LAYER_DIGESTS.get(1), () -> {
            throw new IllegalStateException("layer download failed");
        });
        OciArchiveBuilder builder = new OciArchiveBuilder(blobs, fs, TestPaths.DEFAULT_BASE_DIR);

        assertThrows(IllegalStateException.class,
                () -> builder.streamLayers(imageId(), manifestResult(), layersOnly((layer, blobPath) -> {
                })));
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other side of the pipeline");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ImageId imageId() {
        return ImageId.fromRegistry("registry.example", "library/app", "latest");
    }

    private static ManifestResult manifestResult() {
        Descriptor config = new Descriptor(CONFIG_MEDIA_TYPE, CONFIG_DIGEST, 3);
        List<Descriptor> layers = LAYER_DIGESTS.stream().map(digest -> new Descriptor(LAYER_MEDIA_TYPE, digest, 3))
                .toList();
        Manifest manifest = new Manifest(2, MANIFEST_MEDIA_TYPE, config, layers);
        return new ManifestResult(SHA256 + "a".repeat(64), MANIFEST_MEDIA_TYPE, 0L, manifest);
    }

    /**
     * Dispatcher over per-digest files, with hooks to hold a fetch back or to
     * observe when it completed.
     */
    private static final class BlobSource implements RequestDispatcher {
        private final HostFilesystem fs;
        private final Map<String, Path> files = new ConcurrentHashMap<>();
        private final Map<String, Runnable> before = new ConcurrentHashMap<>();
        private final Map<String, Runnable> after = new ConcurrentHashMap<>();

        private BlobSource(HostFilesystem fs) {
            this.fs = fs;
        }

        private void beforeFetch(String digest, Runnable hook) {
            before.put(digest, hook);
        }

        private void afterFetch(String digest, Runnable hook) {
            after.put(digest, hook);
        }

        private String contentOf(String digest) {
            return "blob of " + digest;
        }

        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes,
                MediaType mediaType) {
            String raw = digest.toString();
            before.getOrDefault(raw, () -> {
            }).run();
            Path file = files.computeIfAbsent(raw, key -> materialize(key, digest.hex()));
            after.getOrDefault(raw, () -> {
            }).run();
            return new FetchResult(digest, mediaType, file);
        }

        private Path materialize(String raw, String hex) {
            try {
                Path file = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "riid-blob-" + hex.charAt(0) + "-",
                        ".bin");
                fs.writeString(file, contentOf(raw));
                return file;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @FunctionalInterface
    private interface LayerCallback {
        void onLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException;
    }

    /** The sink is two methods now; these tests only care about the layers. */
    private static OciArchiveBuilder.LayerSink layersOnly(LayerCallback layers) {
        return new OciArchiveBuilder.LayerSink() {
            @Override
            public void onImageConfig(Path configBlob) {
                // the config is not what these tests are about
            }

            @Override
            public void onLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
                layers.onLayer(layer, blobPath);
            }
        };
    }
}
