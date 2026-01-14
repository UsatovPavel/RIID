package riid.integration.runtime_app;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.app.ImageLoadService;
import riid.app.ImageLoadServiceFactory;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.p2p.P2PExecutor;
import riid.runtime.DockerRuntimeAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class DockerRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String DOCKER = "docker";

    @Test
    void downloadsImageAndLoadsIntoDocker() throws Exception {
        runIgnoreErrors(List.of(DOCKER, "rmi", "-f", "alpine:edge"));

        String refName;
        Path configPath = Files.createTempFile("config-docker-", ".yaml");
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
        Files.writeString(configPath, configYaml);

        var app = ImageLoadServiceFactory.createFromConfig(configPath);
        refName = app.load(REPO, REF, DOCKER);

        Process p = new ProcessBuilder(DOCKER, "images", "--format", "{{.Repository}}:{{.Tag}}")
                .redirectErrorStream(true)
                .start();
        String images = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "docker images failed: " + images);
        boolean found = images.contains("alpine:edge")
                || images.contains("docker.io/library/alpine:edge")
                || images.contains(refName);
        assertTrue(found, "Expected alpine:edge in docker images, got: " + images);
    }

    @Test
    void oneShotLoadAndRun() throws Exception {
        var endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        var app = ImageLoadService.createDefault(
                endpoint,
                new TempFileCacheAdapter(),
                new P2PExecutor.NoOp(),
                java.util.Map.of(DOCKER, new DockerRuntimeAdapter()),
                riid.client.core.config.AuthConfig.DEFAULT_TTL_SECONDS);

        String refName = app.load(REPO, REF, DOCKER);
        run(List.of(DOCKER, "run", "--rm", refName, "true"));
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
        } catch (Exception ignored) {
            // ignore cleanup failures
        }
    }
}

