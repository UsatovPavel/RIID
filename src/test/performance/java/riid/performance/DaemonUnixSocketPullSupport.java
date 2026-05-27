package riid.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import riid.core.config.TestConfigYaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP {@code POST /pull} over a RIID daemon Unix socket (same wire format as
 * {@code curl} in docs).
 */
public final class DaemonUnixSocketPullSupport {

    /**
     * @see TestConfigYaml#ENV_DAEMON_UNIX_SOCKET
     */
    public static final String ENV_DAEMON_UNIX_SOCKET = TestConfigYaml.ENV_DAEMON_UNIX_SOCKET;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DaemonUnixSocketPullSupport() {
    }

    public static void postPull(Path socketPath, Path workDir, String repository, String reference, String runtimeId)
            throws Exception {
        Path bodyFile = workDir.resolve("body-" + repository.replace('/', '-') + ".json");
        String json = "{\"repository\":\"" + repository + "\",\"reference\":\"" + reference + "\",\"runtimeId\":\""
                + runtimeId + "\"}";
        postJson(socketPath, bodyFile, json, repository, "200");
        JsonNode node = MAPPER.readTree(Files.readString(bodyFile));
        assertEquals("success", node.path("status").asText(), "body for " + repository);
    }

    public static void postClean(Path socketPath, Path workDir) throws Exception {
        Path bodyFile = workDir.resolve("body-clean.json");
        String json = "{\"command\":\"CLEAN\"}";
        postJson(socketPath, bodyFile, json, "CLEAN", "200");
        JsonNode node = MAPPER.readTree(Files.readString(bodyFile));
        assertEquals("success", node.path("status").asText(), "body for CLEAN");
    }

    private static void postJson(Path socketPath, Path bodyFile, String json, String operation, String expectedHttpCode)
            throws Exception {
        int maxSec = requestTimeoutSeconds();
        ProcessBuilder pb = new ProcessBuilder("curl", "-sS", "--fail-with-body", "--connect-timeout",
                Integer.toString(Math.min(120, maxSec)), "--max-time", Integer.toString(maxSec), "--unix-socket",
                socketPath.toString(), "-o", bodyFile.toString(), "-w", "%{http_code}", "-X", "POST",
                "http://localhost/pull", "-H", "Content-Type: application/json", "-d", json);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process proc = pb.start();
        AtomicReference<String> errRef = new AtomicReference<>("");
        Thread stderrDrainer = new Thread(() -> {
            try {
                errRef.set(new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                errRef.set(e.toString());
            }
        }, "curl-stderr-" + operation.replace('/', '-'));
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
        String httpCode = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        stderrDrainer.join(TimeUnit.SECONDS.toMillis(maxSec + 60L));
        String err = errRef.get();
        boolean finished = proc.waitFor(2L, TimeUnit.MINUTES);
        assertTrue(finished, "curl did not finish for " + operation + ": " + err);
        assertEquals(0, proc.exitValue(), "curl stderr for " + operation + ": " + err);
        assertEquals(expectedHttpCode, httpCode, "HTTP for " + operation);
    }

    public static int requestTimeoutSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofMinutes(30).toSeconds());
    }
}
