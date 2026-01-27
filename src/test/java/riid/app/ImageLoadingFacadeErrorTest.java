package riid.app;

import java.io.IOException;
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
import riid.app.fs.TestPaths;
import riid.cache.ImageDigest;
import riid.client.api.ManifestResult;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.MediaType;
import riid.dispatcher.FetchResult;
import riid.dispatcher.ImageRef;
import riid.dispatcher.RepositoryName;
import riid.dispatcher.RequestDispatcher;
import riid.runtime.RuntimeAdapter;

class ImageLoadingFacadeErrorTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final ImageDigest IMG_DIGEST = ImageDigest.parse(DIGEST);
    private static final String NOT_USED = "Not used";

    @Test
    void loadWrapsIOExceptionAsAppError() {
        ImageId imageId = ImageId.fromRegistry("registry.example", "repo/app", "latest");
        ManifestResult manifestResult = minimalManifestResult();

        HostFilesystem fs = new FailingHostFilesystem(new IOException("boom"));
        ImageLoadingFacade facade = new ImageLoadingFacade(
                new NoopDispatcher(),
                new RuntimeRegistry(java.util.Map.of()),
                new NoopRegistryClient(),
                fs);

        AppException ex = assertThrows(AppException.class,
                () -> facade.load(manifestResult, new NoopRuntime(), imageId));
        assertTrue(ex.error() instanceof AppError.RuntimeError);
        assertEquals(AppError.RuntimeErrorKind.LOAD_FAILED, ((AppError.RuntimeError) ex.error()).kind());
    }

    @Test
    void loadWrapsInterruptedExceptionAndInterruptsThread() throws Exception {
        ImageId imageId = ImageId.fromRegistry("registry.example", "repo/app", "latest");
        ManifestResult manifestResult = minimalManifestResult();

        HostFilesystem fs = new NioHostFilesystem();
        Path layer = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "riid-layer", ".bin");
        fs.write(layer, new byte[] {1, 2, 3});
        RequestDispatcher dispatcher = new LayerDispatcher(layer.toString());
        ImageLoadingFacade facade = new ImageLoadingFacade(
                dispatcher,
                new RuntimeRegistry(java.util.Map.of()),
                new NoopRegistryClient(),
                fs,
                TestPaths.DEFAULT_BASE_DIR,
                java.util.List.of());

        AppException ex = assertThrows(AppException.class,
                () -> facade.load(manifestResult, new InterruptedRuntime(), imageId));
        assertTrue(ex.error() instanceof AppError.RuntimeError);
        assertEquals(AppError.RuntimeErrorKind.LOAD_FAILED, ((AppError.RuntimeError) ex.error()).kind());
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
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public riid.client.api.BlobResult fetchConfig(String repository, Manifest manifest, java.io.File target) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public riid.client.api.BlobResult fetchBlob(riid.client.api.BlobRequest request, java.io.File target) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public java.util.Optional<Long> headBlob(String repository, String digest) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public riid.client.core.model.manifest.TagList listTags(String repository, Integer n, String last) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public void close() { }
    }

    private static final class NoopDispatcher implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository,
                                      riid.cache.ImageDigest digest,
                                      long sizeBytes,
                                      MediaType mediaType) {
            throw new UnsupportedOperationException(NOT_USED);
        }
    }

    private static final class LayerDispatcher implements RequestDispatcher {
        private final String path;

        private LayerDispatcher(String path) {
            this.path = path;
        }

        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository,
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
        public Path createDirectory(Path dir) throws IOException {
            throw error;
        }

        @Override
        public Path createFile(Path path) throws IOException {
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

        @Override
        public java.io.InputStream newInputStream(Path path) throws IOException {
            throw error;
        }

        @Override
        public java.io.OutputStream newOutputStream(Path path) throws IOException {
            throw error;
        }

        @Override
        public boolean exists(Path path) {
            return false;
        }

        @Override
        public boolean isRegularFile(Path path) {
            return false;
        }

        @Override
        public long size(Path path) throws IOException {
            throw error;
        }

        @Override
        public String probeContentType(Path path) throws IOException {
            throw error;
        }

        @Override
        public java.util.stream.Stream<Path> walk(Path root) throws IOException {
            throw error;
        }

        @Override
        public Path atomicMove(Path source, Path target) throws IOException {
            throw error;
        }
    }
}

