package riid.integration.dispatcher_runtime;

import org.junit.jupiter.api.Test;
import riid.dispatcher.DispatcherRuntimeException;
import riid.dispatcher.DispatcherRuntimeIntegrator;
import riid.dispatcher.FetchResult;
import riid.dispatcher.ImageRef;
import riid.dispatcher.RequestDispatcher;
import riid.runtime.RuntimeAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatcherRuntimeIntegratorTest {

    @Test
    void importsIntoRuntimeAfterValidation() throws Exception {
        Path temp = Files.createTempFile("fetch-", ".bin");
        Files.writeString(temp, "data");
        FetchResult ok = new FetchResult(digestA(), media(), temp.toString());
        RecordingDispatcher dispatcher = new RecordingDispatcher(ok);
        RecordingRuntimeAdapter runtime = new RecordingRuntimeAdapter();

        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(dispatcher);
        FetchResult result = integrator.fetchAndLoad(new ImageRef("repo", "ref", null), runtime);

        assertEquals(temp.toString(), result.path());
        assertEquals(temp, runtime.importedPath);
    }

    @Test
    void failsWhenFileMissing() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(
                new FetchResult(digestA(), media(), "/non/existent/file"));
        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(dispatcher);

        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("repo", "ref", null), new RecordingRuntimeAdapter()));
    }

    @Test
    void failsOnBlankFields() {
        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(
                new RecordingDispatcher(new FetchResult("", "", "")));
        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
    }

    @Test
    void failsOnDirectoryPath() throws Exception {
        Path dir = Files.createTempDirectory("not-file");
        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(
                new RecordingDispatcher(new FetchResult(digestB(), media(), dir.toString())));
        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
    }

    @Test
    void failsOnEmptyFile() throws Exception {
        Path empty = Files.createTempFile("empty-", ".bin");
        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(
                new RecordingDispatcher(new FetchResult(digestC(), media(), empty.toString())));
        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("r", "t", null), new RecordingRuntimeAdapter()));
    }

    private record RecordingDispatcher(FetchResult result) implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            return result;
        }

        @Override
        public FetchResult fetchLayer(String repository, String digest, long sizeBytes, String mediaType) {
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

    private static String digestA() {
        return "sha256:" + "a".repeat(64);
    }

    private static String digestB() {
        return "sha256:" + "b".repeat(64);
    }

    private static String digestC() {
        return "sha256:" + "c".repeat(64);
    }

    private static String media() {
        return "application/octet-stream";
    }
}


