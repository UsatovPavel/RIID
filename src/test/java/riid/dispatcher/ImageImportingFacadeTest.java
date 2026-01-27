package riid.dispatcher;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import riid.app.fs.HostFilesystem;
import riid.app.fs.HostFilesystemTestSupport;
import riid.app.fs.TestPaths;
import riid.client.core.model.manifest.MediaType;
import riid.runtime.RuntimeAdapter;

class ImageImportingFacadeTest {

    @Test
    void importsIntoRuntimeAfterValidation() throws Exception {
        HostFilesystem fs = HostFilesystemTestSupport.create();
        Path temp = TestPaths.tempFile(fs, "fetch-", ".bin");
        fs.writeString(temp, "data");
        FetchResult ok = new FetchResult(digestA(), media(), temp);
        RecordingDispatcher dispatcher = new RecordingDispatcher(ok);
        RecordingRuntimeAdapter runtime = new RecordingRuntimeAdapter();

        ImageImportingFacade integrator = new ImageImportingFacade(dispatcher, fs);
        FetchResult result = integrator.fetchAndLoad(new ImageRef("repo", "ref", null), runtime);

        assertEquals(temp, result.path());
        assertEquals(temp, runtime.importedPath);
    }

    @Test
    void failsWhenFileMissing() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("riid-missing-" + java.util.UUID.randomUUID());
        RecordingDispatcher dispatcher = new RecordingDispatcher(
                new FetchResult(digestA(), media(), missing));
        ImageImportingFacade integrator = new ImageImportingFacade(dispatcher, HostFilesystemTestSupport.create());

        DispatcherRuntimeException ex1 = assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("repo", "ref", null), new RecordingRuntimeAdapter()));
        assertTrue(ex1.getMessage().contains("does not exist"));
    }

    @Test
    void failsOnBlankFields() {
        ImageImportingFacade integrator = new ImageImportingFacade(
                new RecordingDispatcher(new FetchResult(null, null, null)),
                HostFilesystemTestSupport.create());
        DispatcherRuntimeException ex2 = assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
        assertTrue(ex2.getMessage().contains("Missing"));
    }

    @Test
    void failsOnDirectoryPath() throws Exception {
        HostFilesystem fs = HostFilesystemTestSupport.create();
        Path dir = TestPaths.tempDir(fs, "not-file-");
        ImageImportingFacade integrator = new ImageImportingFacade(
                new RecordingDispatcher(new FetchResult(digestB(), media(), dir)),
                fs);
        DispatcherRuntimeException ex3 = assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
        assertTrue(ex3.getMessage().contains("not a regular file"));
    }

    @Test
    void failsOnEmptyFile() throws Exception {
        HostFilesystem fs = HostFilesystemTestSupport.create();
        Path empty = TestPaths.tempFile(fs, "empty-", ".bin");
        ImageImportingFacade integrator = new ImageImportingFacade(
                new RecordingDispatcher(new FetchResult(digestC(), media(), empty)),
                fs);
        DispatcherRuntimeException ex4 = assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
        assertTrue(ex4.getMessage().contains("empty"));
    }

    private record RecordingDispatcher(FetchResult result) implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            return result;
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository, riid.cache.ImageDigest digest,
                                      long sizeBytes, MediaType mediaType) {
            return result;
        }
    }

    private static final class RecordingRuntimeAdapter implements RuntimeAdapter {
        Path importedPath;

        @Override
        public String runtimeId() {
            return "test-runtime";
        }

        @Override
        public void importImage(Path imagePath) throws IOException {
            this.importedPath = imagePath;
        }
    }

    private static riid.cache.ImageDigest digestA() {
        return riid.cache.ImageDigest.parse("sha256:" + "a".repeat(64));
    }

    private static riid.cache.ImageDigest digestB() {
        return riid.cache.ImageDigest.parse("sha256:" + "b".repeat(64));
    }

    private static riid.cache.ImageDigest digestC() {
        return riid.cache.ImageDigest.parse("sha256:" + "c".repeat(64));
    }

    private static MediaType media() {
        return MediaType.OCI_IMAGE_LAYER;
    }
}


