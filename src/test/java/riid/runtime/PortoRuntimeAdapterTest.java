package riid.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortoRuntimeAdapterTest {

    @Test
    void missingFileThrowsIOException() {
        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        Path missing = Path.of("non-existing-portable-image.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    @Tag("porto")
    void importsLayerViaPortoctl() throws Exception {
        String tar = System.getenv("PORTO_LAYER_TAR");
        assumeTrue(tar != null && !tar.isBlank(), "Set PORTO_LAYER_TAR to a layer archive");
        Path archive = Path.of(tar);
        assumeTrue(Files.exists(archive), "Layer archive missing: " + archive);
        var fileName = archive.getFileName();
        if (fileName == null) {
            throw new IOException("Layer archive has no filename: " + archive);
        }
        String layerName = fileName.toString();

        // best-effort cleanup: try remove by filename
        runIgnoreErrors(List.of("portoctl", "layer", "-R", layerName));

        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        adapter.importImage(archive);

        Process p = new ProcessBuilder("portoctl", "layer", "-L")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "portoctl layer -L failed: " + out);
        assertTrue(out.contains(layerName), "Imported layer not listed: " + out);
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

