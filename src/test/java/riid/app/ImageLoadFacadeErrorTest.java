package riid.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import riid.app.error.AppError;
import riid.app.error.AppException;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.cache.ImageDigest;
import riid.client.api.ManifestResult;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.MediaType;
import riid.dispatcher.FetchResult;
import riid.dispatcher.ImageRef;
import riid.dispatcher.RequestDispatcher;
import riid.runtime.RuntimeAdapter;

class ImageLoadFacadeErrorTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final ImageDigest IMG_DIGEST = ImageDigest.parse(DIGEST);

    @Test
    void loadWrapsIOExceptionAsAppError() {
        ImageId imageId = ImageId.fromRegistry("registry.example", "repo/app", "latest");
        ManifestResult manifestResult = minimalManifestResult();

        HostFilesystem fs = new FailingHostFilesystem(new IOException("boom"));
        ImageLoadFacade facade = new ImageLoadFacade(new NoopDispatcher(), new RuntimeRegistry(java.util.Map.of()),
                new NoopRegistryClient(), fs);

        AppException ex = assertThrows(AppException.class,
                () -> facade.load(manifestResult, new NoopRuntime(), imageId));
        assertTrue(ex.error() instanceof AppError.Runtime);
        assertEquals(AppError.RuntimeKind.LOAD_FAILED, ((AppError.Runtime) ex.error()).kind());
    }

    @Test
    void loadWrapsInterruptedExceptionAndInterruptsThread() throws Exception {
        ImageId imageId = ImageId.fromRegistry("registry.example", "repo/app", "latest");
        ManifestResult manifestResult = minimalManifestResult();

        Path layer = Files.createTempFile("riid-layer", ".bin");
        Files.write(layer, new byte[] {1, 2, 3});
        RequestDispatcher dispatcher = new LayerDispatcher(layer.toString());
        HostFilesystem fs = new NioHostFilesystem(null);
        ImageLoadFacade facade = new ImageLoadFacade(dispatcher, new RuntimeRegistry(java.util.Map.of()),
                new NoopRegistryClient(), fs);

        AppException ex = assertThrows(AppException.class,
                () -> facade.load(manifestResult, new InterruptedRuntime(), imageId));
        assertTrue(ex.error() instanceof AppError.Runtime);
        assertEquals(AppError.RuntimeKind.LOAD_FAILED, ((AppError.Runtime) ex.error()).kind());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted(); // clear for other tests
    }

    private static ManifestResult minimalManifestResult() {
        Descriptor config = new Descriptor("application/vnd.oci.image.config.v1+json", DIGEST, 3);
        Manifest manifest = new Manifest(2, "application/vnd.oci.image.manifest.v1+json", config, List.of());
        return new ManifestResult(DIGEST, "application/vnd.oci.image.manifest.v1+json", 3L, manifest);
    }

    private static final class NoopRegistryClient implements riid.client.api.RegistryClient {
        @Override
        public ManifestResult fetchManifest(String repository, String reference) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public riid.client.api.BlobResult fetchConfig(String repository, Manifest manifest, java.io.File target) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public riid.client.api.BlobResult fetchBlob(riid.client.api.BlobRequest request, java.io.File target) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public java.util.Optional<Long> headBlob(String repository, String digest) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public riid.client.core.model.manifest.TagList listTags(String repository, Integer n, String last) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public void close() { }
    }

    private static final class NoopDispatcher implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public FetchResult fetchLayer(String repository,
                                     riid.cache.ImageDigest digest,
                                     long sizeBytes,
                                     MediaType mediaType) {
            throw new UnsupportedOperationException("Not used");
        }
    }

    private static final class LayerDispatcher implements RequestDispatcher {
        private final String path;

        private LayerDispatcher(String path) {
            this.path = path;
        }

        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException("Not used");
        }

        @Override
        public FetchResult fetchLayer(String repository,
                                     riid.cache.ImageDigest digest,
                                     long sizeBytes,
                                     MediaType mediaType) {
            return new FetchResult(IMG_DIGEST, mediaType, Path.of(path));
        }
    }

    private static final class NoopRuntime implements RuntimeAdapter {
        @Override
        public String runtimeId() {
            return "noop";
        }

        @Override
        public void importImage(Path imagePath) { }
    }

    private static final class InterruptedRuntime implements RuntimeAdapter {
        @Override
        public String runtimeId() {
            return "noop";
        }

        @Override
        public void importImage(Path imagePath) throws InterruptedException {
            throw new InterruptedException("boom");
        }
    }

    private static final class FailingHostFilesystem implements HostFilesystem {
        private final IOException error;

        private FailingHostFilesystem(IOException error) {
            this.error = error;
        }

        @Override
        public Path createTempDirectory(String prefix) throws IOException {
            throw error;
        }

        @Override
        public Path createTempFile(String prefix, String suffix) throws IOException {
            throw error;
        }

        @Override
        public Path createDirectories(Path dir) throws IOException {
            throw error;
        }

        @Override
        public Path copy(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
            throw error;
        }

        @Override
        public Path write(Path path, byte[] bytes) throws IOException {
            throw error;
        }

        @Override
        public Path writeString(Path path, String content) throws IOException {
            throw error;
        }
    }
}

