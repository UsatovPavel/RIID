package riid.integration.runtime_app;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.app.CliApplication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end via CLI with real podman runtime.
 * Requires podman and network access to Docker Hub.
 */
@Tag("local")
class CliPodmanIntegrationTest {

    private static final String PODMAN = "podman";

    @Test
    void cliLoadsBusyboxIntoPodman() throws Exception {
        // ensure clean slate
        runIgnoreErrors(PODMAN, "rmi", "-f", "alpine:edge", "busybox:latest");

        Path config = Files.createTempFile("config-", ".yaml");
        Files.writeString(config, """
                client:
                  http: {}
                  auth: {}
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 2
                """);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        CliApplication cli = CliApplication.createDefault();

        int code = cli.run(new String[]{
                "--config", config.toString(),
                "--repo", "library/busybox",
                "--tag", "latest",
                "--runtime", PODMAN
        });

        if (code != 0) {
            throw new AssertionError("CLI exit " + code + "\nSTDOUT:\n" + outBuf + "\nSTDERR:\n" + errBuf);
        }

        Process p = new ProcessBuilder(PODMAN, "images", "--format", "{{.Repository}}:{{.Tag}}")
                .redirectErrorStream(true)
                .start();
        String images = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int imagesCode = p.waitFor();
        assertEquals(0, imagesCode, "podman images failed: " + images);
        assertTrue(images.contains("busybox:latest") || images.contains("docker.io/library/busybox:latest"),
                "Expected busybox:latest in podman images, got: " + images);
    }

    private static void runIgnoreErrors(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

