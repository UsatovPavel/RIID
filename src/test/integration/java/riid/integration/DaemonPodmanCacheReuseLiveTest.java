package riid.integration;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.app.cli.CliApplication;
import riid.app.core.config.AppConfig;
import riid.app.core.model.ImageId;
import riid.app.daemon.DaemonServer;
import riid.app.service.ImageLoadingFacade;
import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;
import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration: daemon + podman + Docker Hub.
 * <p>
 * Scenario: 1) pull 10MB-class image via daemon 2) remove podman containers
 * (podman rm -af) 3) pull the same image again 4) assert dispatcher cache
 * metric increases on second pull.
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
@Tag("live")
class DaemonPodmanCacheReuseLiveTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CACHE_FETCHES = Pattern
            .compile("(?m)^riid_dispatcher_layer_fetches_total\\{[^}]*source=\"cache\"[^}]*}\\s+([0-9.eE+-]+)\\s*$");
    private static final String REPOSITORY = "library/jobber";
    private static final String REFERENCE = "latest";
    private static final String RUNTIME_ID = "podman";

    @Test
    void secondPullUsesCacheAfterPodmanContainerCleanup() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");
        assumeTrue(commandAvailable("podman"), "podman must be on PATH");

        Path workDir = Files.createTempDirectory("daemon-cache-reuse-");
        Path configPath = Files.createTempFile("daemon-cache-reuse-", ".yaml");
        Path tempDir = Files.createTempDirectory("daemon-cache-reuse-temp-");
        Path socketPath = workDir.resolve("riid.sock");
        Path body1 = workDir.resolve("pull-1-body.json");
        Path body2 = workDir.resolve("pull-2-body.json");

        Files.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, tempDir.toString()));

        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        String registry = TestRegistryConfig.registryName();
        ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(configPath, null, prom);
        CliApplication.ImageLoader loader = (repo, ref, runtimeId) -> facade
                .load(ImageId.fromRegistry(registry, repo, ref), runtimeId);

        DaemonServer daemon = new DaemonServer(socketPath.toString(), "127.0.0.1", 0, loader, Set.of(RUNTIME_ID), 4,
                8192, Duration.ofMinutes(10), AppConfig.OverloadPolicy.REJECT, prom);

        try {
            daemon.start();
            int metricsPort = daemon.getMetricsListenPort();
            assertTrue(metricsPort > 0, "metrics TCP port must be bound");

            double cacheBefore = cacheFetches(metricsPort);

            postPull(socketPath, body1, REPOSITORY, REFERENCE, RUNTIME_ID);
            double cacheAfterFirstPull = cacheFetches(metricsPort);

            runOrFail("podman", "rm", "-af");

            postPull(socketPath, body2, REPOSITORY, REFERENCE, RUNTIME_ID);
            double cacheAfterSecondPull = cacheFetches(metricsPort);

            assertTrue(cacheAfterSecondPull > cacheAfterFirstPull,
                    "second pull should increase dispatcher cache fetches; before=" + cacheAfterFirstPull + ", after="
                            + cacheAfterSecondPull);
            assertTrue(cacheAfterSecondPull > cacheBefore, "cache fetches should increase from baseline; before="
                    + cacheBefore + ", after=" + cacheAfterSecondPull);
        } finally {
            daemon.stop();
            facade.close();
            TestFilesystemSupport.deleteRecursive(workDir);
            TestFilesystemSupport.deleteRecursive(tempDir);
            try {
                Files.deleteIfExists(configPath);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void postPull(Path socketPath, Path bodyFile, String repository, String reference, String runtimeId)
            throws Exception {
        String json = "{\"repository\":\"" + repository + "\",\"reference\":\"" + reference + "\",\"runtimeId\":\""
                + runtimeId + "\"}";
        ProcessBuilder pb = new ProcessBuilder("curl", "-sS", "--fail-with-body", "--unix-socket",
                socketPath.toString(), "-o", bodyFile.toString(), "-w", "%{http_code}", "-X", "POST",
                "http://localhost/pull", "-H", "Content-Type: application/json", "-d", json);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = pb.start();
        String httpCode = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(600, TimeUnit.SECONDS);
        assertTrue(finished, "curl did not finish: " + err);
        assertEquals(0, process.exitValue(), "curl stderr: " + err);
        assertEquals("200", httpCode, "pull must return HTTP 200");
        JsonNode body = MAPPER.readTree(java.nio.file.Files.readString(bodyFile));
        assertEquals("success", body.path("status").asText(), "pull body");
    }

    @SuppressWarnings("PMD.CloseResource")
    private static double cacheFetches(int metricsPort) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + metricsPort + "/metrics"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode(), "metrics endpoint");

        Matcher m = CACHE_FETCHES.matcher(resp.body());
        if (!m.find()) {
            return 0.0;
        }
        return Double.parseDouble(m.group(1));
    }

    private static void runOrFail(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }

    private static boolean commandAvailable(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            int code = p.waitFor();
            return code == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
