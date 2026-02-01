package riid.integration.runtime_app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.CliApplication;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

/**
 * End-to-end via CLI with real podman runtime.
 * Requires podman and network access to Docker Hub.
 */
@Tag("filesystem")
@Tag("local")
class CliPodmanIntegrationTest {

    private static final String PODMAN = "podman";

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void cliLoadsBusyboxIntoPodman() throws Exception {
        // ensure clean slate
        runIgnoreErrors(PODMAN, "rmi", "-f", "alpine:edge", "busybox:latest");

        HostFilesystem fs = new NioHostFilesystem();
        Path config = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
        fs.writeString(config, """
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
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        int code;
        try (PrintStream testOut = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
             PrintStream testErr = new PrintStream(errBuf, true, StandardCharsets.UTF_8)) {
            System.setOut(testOut);
            System.setErr(testErr);

        CliApplication cli = CliApplication.createDefault();
            code = cli.run(new String[]{
                "--config", config.toString(),
                "--repo", "library/busybox",
                "--tag", "latest",
                "--runtime", PODMAN
        });
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        if (code != 0) {
            String podmanVersion = runCapture(PODMAN, "--version");
            String podmanInfo = runCapture(PODMAN, "info");
            throw new AssertionError("CLI exit " + code
                    + "\nSTDOUT:\n" + outBuf
                    + "\nSTDERR:\n" + errBuf
                    + "\nPODMAN VERSION:\n" + podmanVersion
                    + "\nPODMAN INFO:\n" + podmanInfo);
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

    private static String runCapture(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return "failed to run " + String.join(" ", cmd) + ": " + e.getMessage();
        }
    }
}

