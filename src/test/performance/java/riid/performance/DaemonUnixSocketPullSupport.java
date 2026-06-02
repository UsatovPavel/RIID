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
        PullResult result = postPullCapture(socketPath, workDir, repository, reference, runtimeId);
        assertTrue(result.finished(), "curl did not finish for " + repository + ": " + result.stderr());
        assertEquals(0, result.exitCode(), "curl stderr for " + repository + ": " + result.stderr());
        assertEquals(200, result.httpStatus(), "HTTP for " + repository + " body=" + result.body());
        JsonNode node = MAPPER.readTree(result.body());
        assertEquals("success", node.path("status").asText(), "body for " + repository);
    }

    public static PullResult postPullCapture(Path socketPath, Path workDir, String repository, String reference,
            String runtimeId) throws Exception {
        Path bodyFile = workDir.resolve("body-" + repository.replace('/', '-') + ".json");
        String json = "{\"repository\":\"" + repository + "\",\"reference\":\"" + reference + "\",\"runtimeId\":\""
                + runtimeId + "\"}";
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
        }, "curl-stderr-" + repository.replace('/', '-'));
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
        String httpCodeRaw = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        stderrDrainer.join(TimeUnit.SECONDS.toMillis(maxSec + 60L));
        String err = errRef.get();
        boolean finished = proc.waitFor(2L, TimeUnit.MINUTES);
        int exitCode = finished ? proc.exitValue() : -1;
        int httpStatus = parseHttpStatus(httpCodeRaw);
        String body = Files.exists(bodyFile) ? Files.readString(bodyFile) : "";
        return new PullResult(finished, exitCode, httpStatus, body, err);
    }

    private static int parseHttpStatus(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static int requestTimeoutSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofMinutes(30).toSeconds());
    }

    public record PullResult(boolean finished, int exitCode, int httpStatus, String body, String stderr) {
    }
}
