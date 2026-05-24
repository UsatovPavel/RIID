package riid.app.daemon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.MDC;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import riid.app.core.config.AppConfig;
import riid.app.core.model.ImageId;
import riid.app.service.LoadOutcome;
import riid.core.fs.TestFilesystemSupport;
import riid.core.logging.LogContextKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Test
    void postPullOverUnixDomainSocketReturnsSuccess() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path dir = Files.createTempDirectory("riid-daemon-uds");
        Path socketPath = dir.resolve("riid.sock");
        Path bodyFile = dir.resolve("body.json");

        AtomicReference<String> traceInLoader = new AtomicReference<>();
        DaemonServer daemon = newDaemonServer(socketPath, traceInLoader);

        try {
            daemon.start();
            String json = "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}";
            ProcessBuilder pb = curlPullBuilder(socketPath, bodyFile, json, List.of());
            runCurl(pb);

            JsonNode node = MAPPER.readTree(Files.readString(bodyFile));
            assertEquals("success", node.path("status").asText());
            assertEquals("registry-1.docker.io/library/busybox:latest", node.path("imagePath").asText());
            assertNotNull(traceInLoader.get());
            assertTrue(UUID_RE.matcher(traceInLoader.get()).matches(), traceInLoader.get());
        } finally {
            daemon.stop();
            TestFilesystemSupport.deleteRecursive(dir);
        }
    }

    @Test
    void postPullPropagatesXTraceIdToLoaderThread() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path dir = Files.createTempDirectory("riid-daemon-uds-trace");
        Path socketPath = dir.resolve("riid.sock");
        Path bodyFile = dir.resolve("body.json");

        AtomicReference<String> traceInLoader = new AtomicReference<>();
        DaemonServer daemon = newDaemonServer(socketPath, traceInLoader);

        try {
            daemon.start();
            String json = "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}";
            ProcessBuilder pb = curlPullBuilder(
                    socketPath, bodyFile, json, List.of("X-Trace-Id: uds-corr-xyz"));
            runCurl(pb);

            JsonNode node = MAPPER.readTree(Files.readString(bodyFile));
            assertEquals("success", node.path("status").asText());
            assertEquals("uds-corr-xyz", traceInLoader.get());
        } finally {
            daemon.stop();
            TestFilesystemSupport.deleteRecursive(dir);
        }
    }

    private static DaemonServer newDaemonServer(Path socketPath, AtomicReference<String> traceInLoader) {
        return new DaemonServer(
                socketPath.toString(),
                "127.0.0.1",
                0,
                (repo, ref, rt) -> {
                    traceInLoader.set(MDC.get(LogContextKeys.TRACE_ID));
                    return new LoadOutcome(ImageId.fromRegistry("registry-1.docker.io", repo, ref), -1L);
                },
                Set.of("podman"),
                4,
                8192,
                Duration.ofSeconds(30),
                AppConfig.OverloadPolicy.REJECT,
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    private static ProcessBuilder curlPullBuilder(
            Path socketPath, Path bodyFile, String jsonBody, List<String> extraHeaders) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(
                List.of(
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
                        "http://localhost/pull"));
        for (String h : extraHeaders) {
            cmd.add("-H");
            cmd.add(h);
        }
        cmd.add("-H");
        cmd.add("Content-Type: application/json");
        cmd.add("-d");
        cmd.add(jsonBody);
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE);
    }

    private static void runCurl(ProcessBuilder pb) throws Exception {
        Process p = pb.start();
        String httpCode = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        assertTrue(finished, "curl did not finish: " + err);
        assertEquals(0, p.exitValue(), "curl stderr: " + err);
        assertEquals("200", httpCode);
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
                (repo, ref, rt) ->
                        new LoadOutcome(ImageId.fromRegistry("registry-1.docker.io", repo, ref), -1L),
                Set.of("podman"),
                4,
                8192,
                Duration.ofSeconds(30),
                AppConfig.OverloadPolicy.REJECT,
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        DaemonServer daemonB = new DaemonServer(
                socketPath.toString(),
                "127.0.0.1",
                0,
                (repo, ref, rt) ->
                        new LoadOutcome(ImageId.fromRegistry("registry-1.docker.io", repo, ref), -1L),
                Set.of("podman"),
                4,
                8192,
                Duration.ofSeconds(30),
                AppConfig.OverloadPolicy.REJECT,
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

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
