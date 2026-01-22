package riid.dispatcher;

import org.junit.jupiter.api.Test;
import riid.runtime.RuntimeAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import riid.client.core.model.manifest.MediaType;

class ImageImportFacadeTest {

    @Test
    void importsIntoRuntimeAfterValidation() throws Exception {
        Path temp = Files.createTempFile("fetch-", ".bin");
        Files.writeString(temp, "data");
        FetchResult ok = new FetchResult(digestA(), media(), temp);
        RecordingDispatcher dispatcher = new RecordingDispatcher(ok);
        RecordingRuntimeAdapter runtime = new RecordingRuntimeAdapter();

        ImageImportFacade integrator = new ImageImportFacade(dispatcher);
        FetchResult result = integrator.fetchAndLoad(new ImageRef("repo", "ref", null), runtime);

        assertEquals(temp, result.path());
        assertEquals(temp, runtime.importedPath);
    }

    @Test
    void failsWhenFileMissing() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(
                new FetchResult(digestA(), media(), Path.of("/non/existent/file")));
        ImageImportFacade integrator = new ImageImportFacade(dispatcher);

        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("repo", "ref", null), new RecordingRuntimeAdapter()));
    }

    @Test
    void failsOnBlankFields() {
        ImageImportFacade integrator = new ImageImportFacade(
                new RecordingDispatcher(new FetchResult(null, null, null)));
        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
    }

    @Test
    void failsOnDirectoryPath() throws Exception {
        Path dir = Files.createTempDirectory("not-file");
        ImageImportFacade integrator = new ImageImportFacade(
                new RecordingDispatcher(new FetchResult(digestB(), media(), dir)));
        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
    }

    @Test
    void failsOnEmptyFile() throws Exception {
        Path empty = Files.createTempFile("empty-", ".bin");
        ImageImportFacade integrator = new ImageImportFacade(
                new RecordingDispatcher(new FetchResult(digestC(), media(), empty)));
        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
    }

    private record RecordingDispatcher(FetchResult result) implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            return result;
        }

        @Override
        public FetchResult fetchLayer(String repository, riid.cache.ImageDigest digest, long sizeBytes, MediaType mediaType) {
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


