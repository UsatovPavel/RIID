package riid.performance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import riid.core.config.TestConfigYaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP {@code POST /pull} over a RIID daemon Unix socket (same wire format as {@code curl} in docs).
 */
public final class DaemonUnixSocketPullSupport {

    /** @see TestConfigYaml#ENV_DAEMON_UNIX_SOCKET */
    public static final String ENV_DAEMON_UNIX_SOCKET = TestConfigYaml.ENV_DAEMON_UNIX_SOCKET;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DaemonUnixSocketPullSupport() { }

    public static void postPull(
            Path socketPath,
            Path workDir,
            String repository,
            String reference,
            String runtimeId) throws Exception {
        Path bodyFile = workDir.resolve("body-" + repository.replace('/', '-') + ".json");
        String json = "{\"repository\":\"" + repository + "\",\"reference\":\"" + reference
                + "\",\"runtimeId\":\"" + runtimeId + "\"}";
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
        Process proc = pb.start();
        String httpCode = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = proc.waitFor(requestTimeoutSeconds(), TimeUnit.SECONDS);
        assertTrue(finished, "curl did not finish for " + repository + ": " + err);
        assertEquals(0, proc.exitValue(), "curl stderr for " + repository + ": " + err);
        assertEquals("200", httpCode, "HTTP for " + repository);
        JsonNode node = MAPPER.readTree(Files.readString(bodyFile));
        assertEquals("success", node.path("status").asText(), "body for " + repository);
    }

    public static int requestTimeoutSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofMinutes(30).toSeconds());
    }
}
