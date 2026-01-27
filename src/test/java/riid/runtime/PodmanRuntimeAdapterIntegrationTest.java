package riid.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.ImageId;
import riid.app.ImageLoadingFacade;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;
import riid.app.fs.HostFilesystemTestSupport;
import riid.cache.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.p2p.P2PExecutor;

@Tag("local")
class PodmanRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String PODMAN = "podman";

    @Test
    void downloadsImageAndLoadsIntoPodman() throws Exception {
        // Clean up image if already present
        runIgnoreErrors(List.of(PODMAN, "rmi", "-f", "alpine:edge"));

        // Use high-level service to fetch and import
        ImageId loadedId;
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, "config-", ".yaml");
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
                """;
        fs.writeString(configPath, configYaml);

        var app = ImageLoadingFacade.createFromConfig(configPath);
        ImageId imageId = ImageId.fromRegistry("registry-1.docker.io", REPO, REF);
        loadedId = app.load(imageId, PODMAN);

        Process p = new ProcessBuilder(PODMAN, "images", "--format", "{{.Repository}}:{{.Tag}}")
                .redirectErrorStream(true)
                .start();
        String images = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "podman images failed: " + images);
        boolean found = images.contains("alpine:edge")
                || images.contains("docker.io/library/alpine:edge")
                || images.contains(loadedId.toString());
        assertTrue(found, "Expected alpine:edge in podman images, got: " + images);
    }

    /**
     * End-to-end using App facade: load via dispatcher/runtime and then run with podman.
     */
    @Test
    void oneShotLoadAndRun() throws Exception {
        // Build app with podman runtime only; dispatcher falls back to registry
        var endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        var app = ImageLoadingFacade.createDefault(
                endpoint,
                new TempFileCacheAdapter(),
                new P2PExecutor.NoOp(),
                java.util.Map.of(PODMAN, new PodmanRuntimeAdapter()),
                HostFilesystemTestSupport.create());

        ImageId imageId = ImageId.fromRegistry(endpoint.registryName(), REPO, REF);
        ImageId loadedId = app.load(imageId, "podman");
        // Verify the image can run a trivial command
        run(List.of(PODMAN, "run", "--rm", loadedId.toString(), "true"));
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed: " + cmd + " -> " + code + " output: " + out);
        }
    }

    private static void runIgnoreErrors(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (IOException | InterruptedException ignored) {
            // ignore cleanup failures
        }
    }
}

