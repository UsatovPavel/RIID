package riid.app.daemon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.app.core.config.AppConfig;
import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link DaemonServer} with a real {@code UnixDomainServerConnector} (not {@code LocalConnector}).
 * Requires Linux, a filesystem path for the socket, and {@code curl} on {@code PATH}.
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
class DaemonPullUnixSocketTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void postPullOverUnixDomainSocketReturnsSuccess() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path dir = Files.createTempDirectory("riid-daemon-uds");
        Path socketPath = dir.resolve("riid.sock");
        Path bodyFile = dir.resolve("body.json");

        DaemonServer daemon = new DaemonServer(
                socketPath.toString(),
                "127.0.0.1",
                0,
                (repo, ref, rt) -> "/var/tmp/riid-test.tar",
                Set.of("podman"),
                4,
                8192,
                Duration.ofSeconds(30),
                AppConfig.OverloadPolicy.REJECT);

        try {
            daemon.start();
            String json = "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}";
            ProcessBuilder pb = new ProcessBuilder(
                    "curl",
                    "-sS",
                    "--fail-with-body",
                    "--unix-socket",
                    socketPath.toString(),
                    "-o",
                    bodyFile.toString(),
                    "-w",
                    "%{http_code}",
                    "-X",
                    "POST",
                    "http://localhost/pull",
                    "-H",
                    "Content-Type: application/json",
                    "-d",
                    json);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            Process p = pb.start();
            String httpCode = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            assertTrue(finished, "curl did not finish: " + err);
            assertEquals(0, p.exitValue(), "curl stderr: " + err);
            assertEquals("200", httpCode);

            JsonNode node = MAPPER.readTree(Files.readString(bodyFile));
            assertEquals("success", node.path("status").asText());
            assertEquals("/var/tmp/riid-test.tar", node.path("imagePath").asText());
        } finally {
            daemon.stop();
            TestFilesystemSupport.deleteRecursive(dir);
        }
    }

    @Test
    void startFailsWhenUnixSocketAlreadyOwnedByRunningDaemon() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path dir = Files.createTempDirectory("riid-daemon-uds-owned");
        Path socketPath = dir.resolve("riid.sock");

        DaemonServer daemonA = new DaemonServer(
                socketPath.toString(),
                "127.0.0.1",
                0,
                (repo, ref, rt) -> "/var/tmp/riid-test-a.tar",
                Set.of("podman"),
                4,
                8192,
                Duration.ofSeconds(30),
                AppConfig.OverloadPolicy.REJECT);

        DaemonServer daemonB = new DaemonServer(
                socketPath.toString(),
                "127.0.0.1",
                0,
                (repo, ref, rt) -> "/var/tmp/riid-test-b.tar",
                Set.of("podman"),
                4,
                8192,
                Duration.ofSeconds(30),
                AppConfig.OverloadPolicy.REJECT);

        try {
            daemonA.start();
            IOException error = assertThrows(IOException.class, daemonB::start);
            assertTrue(error.getMessage().contains("already in use"));
        } finally {
            daemonA.stop();
            TestFilesystemSupport.deleteRecursive(dir);
        }
    }
}
