package riid.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortoRuntimeAdapterTest {

    @Test
    void missingFileThrowsIOException() {
        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        Path missing = Path.of("non-existing-portable-image.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    @Tag("local")
    @EnabledIfEnvironmentVariable(named = "PORTO_LAYER_TAR", matches = ".*")
    void importsLayerViaPortoctl() throws Exception {
        String tar = System.getenv("PORTO_LAYER_TAR");
        Path archive = Path.of(tar);
        if (!Files.exists(archive)) {
            throw new IOException("Layer archive missing: " + archive);
        }

        // best-effort cleanup: try remove by filename
        runIgnoreErrors(List.of("portoctl", "layer", "-R", archive.getFileName().toString()));

        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        adapter.importImage(archive);

        Process p = new ProcessBuilder("portoctl", "layer", "-L")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        assertEquals(0, code, "portoctl layer -L failed: " + out);
        assertTrue(out.contains(archive.getFileName().toString()), "Imported layer not listed: " + out);
    }

    private static void runIgnoreErrors(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ignored) {
            // ignore cleanup failures
        }
    }
}

