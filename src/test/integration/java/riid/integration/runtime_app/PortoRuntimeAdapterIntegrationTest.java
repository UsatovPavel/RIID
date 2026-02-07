package riid.integration.runtime_app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import riid.app.ImageId;
import riid.app.ImageLoadingFacade;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

@Tag("filesystem")
@Tag("local")
@EnabledIfEnvironmentVariable(named = "PORTO_INTEGRATION", matches = ".*")
class PortoRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";

    @Test
    void downloadsImageAndLoadsIntoPorto() throws Exception {
        List<String> before = listLayers();

        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
        String configYaml = """
                client:
                  http:
                    connectTimeout: PT5S
                    requestTimeout: PT10S
                    maxRetries: 2
                    retryIdempotentOnly: true
                    followRedirects: true
                    initialBackoff: PT0.2S
                    maxBackoff: PT2S
                  auth:
                    defaultTokenTtlSeconds: 600
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 3
                app:
                  tempDirectory: "build/test-fs"
                """;
        fs.writeString(configPath, configYaml);

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            ImageId imageId = ImageId.fromRegistry("registry-1.docker.io", REPO, REF);
            app.load(imageId, "porto");
        }

        List<String> after = listLayers();
        List<String> newLayers = new ArrayList<>(after);
        newLayers.removeAll(before);
        assertTrue(!newLayers.isEmpty(), "Expected new layers after load, got: " + after);

        for (String layer : newLayers) {
            runIgnoreErrors(List.of("portoctl", "layer", "-R", layer));
        }
    }

    private static List<String> listLayers() throws Exception {
        Process p = new ProcessBuilder("portoctl", "layer", "-L")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "portoctl layer -L failed: " + out);
        return out.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
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
