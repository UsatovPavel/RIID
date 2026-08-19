package riid.app.daemon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import riid.app.core.config.AppConfig;
import riid.app.core.model.ImageId;
import riid.app.service.LoadOutcome;
import riid.core.fs.TestFilesystemSupport;
import riid.runtime.adapter.RuntimeId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * After a successful mocked {@code POST /pull}, {@code GET /metrics} on the TCP
 * connector must scrape valid Prometheus text with dashboard-critical series
 * names (no registry / real image pull).
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
class DaemonPullMetricsEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Tar size in the {@code mib_10_50} bucket so tar category counters are
     * emitted.
     */
    private static final long MOCK_TAR_BYTES = 12L * 1024 * 1024;

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void getMetricsAfterMockedPullContainsKeyPrometheusSeries() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path dir = Files.createTempDirectory("riid-daemon-metrics");
        Path socketPath = dir.resolve("riid.sock");
        Path bodyFile = dir.resolve("body.json");

        DaemonServer daemon = new DaemonServer(socketPath.toString(), "127.0.0.1", 0,
                (repo, ref, rt) -> new LoadOutcome(ImageId.fromRegistry("registry-1.docker.io", repo, ref),
                        MOCK_TAR_BYTES),
                Set.of(RuntimeId.PODMAN), 4, 8192, Duration.ofSeconds(30), AppConfig.OverloadPolicy.REJECT,
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        try {
            daemon.start();
            int metricsPort = daemon.getMetricsListenPort();
            assertTrue(metricsPort > 0, "metrics TCP port must be bound");

            String json = "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}";
            ProcessBuilder pb = new ProcessBuilder("curl", "-sS", "--fail-with-body", "--unix-socket",
                    socketPath.toString(), "-o", bodyFile.toString(), "-w", "%{http_code}", "-X", "POST",
                    "http://localhost/pull", "-H", "Content-Type: application/json", "-d", json);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            Process p = pb.start();
            String httpCode = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            assertTrue(finished, "curl did not finish: " + err);
            assertEquals(0, p.exitValue(), "curl stderr: " + err);
            assertEquals("200", httpCode);

            assertEquals("success", MAPPER.readTree(Files.readString(bodyFile)).path("status").asText());

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest getMetrics = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + metricsPort + "/metrics")).timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> scrape = client.send(getMetrics,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, scrape.statusCode());
            assertTrue(scrape.headers().firstValue("content-type").orElse("").startsWith("text/plain"),
                    "Content-Type should be Prometheus text; got: " + scrape.headers().map());

            String body = scrape.body();
            assertTrue(body.contains("# HELP"), "expected Prometheus exposition with HELP lines: " + bodySnippet(body));
            assertTrue(body.contains("riid_daemon_pull_seconds_count"),
                    "expected POST /pull timer count: " + bodySnippet(body));
            assertTrue(body.contains("riid_image_load_seconds_count"),
                    "expected pipeline timer count: " + bodySnippet(body));
            assertTrue(body.contains("riid_image_load_tar_size_category_total"),
                    "expected tar size category counter after mocked load with known tar size: " + bodySnippet(body));
        } finally {
            daemon.stop();
            TestFilesystemSupport.deleteRecursive(dir);
        }
    }

    private static String bodySnippet(String body) {
        int max = 800;
        if (body.length() <= max) {
            return body;
        }
        return body.substring(0, max) + "...";
    }
}
