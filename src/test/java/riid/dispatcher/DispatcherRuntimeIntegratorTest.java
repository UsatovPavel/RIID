package riid.dispatcher;

import org.junit.jupiter.api.Test;
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
        RecordingDispatcher dispatcher = new RecordingDispatcher(new FetchResult("sha256:" + "a".repeat(64),
                "application/octet-stream", temp.toString()));
        RecordingRuntimeAdapter runtime = new RecordingRuntimeAdapter();

        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(dispatcher);
        FetchResult result = integrator.fetchAndLoad(new ImageRef("repo", "ref", true), runtime);

        assertEquals(temp.toString(), result.path());
        assertEquals(temp, runtime.importedPath);
    }

    @Test
    void failsWhenFileMissing() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(new FetchResult("sha256:" + "a".repeat(64),
                "application/octet-stream", "/non/existent/file"));
        DispatcherRuntimeIntegrator integrator = new DispatcherRuntimeIntegrator(dispatcher);

        assertThrows(DispatcherRuntimeException.class,
                () -> integrator.fetchAndLoad(new ImageRef("repo", "ref", true), new RecordingRuntimeAdapter()));
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
}


