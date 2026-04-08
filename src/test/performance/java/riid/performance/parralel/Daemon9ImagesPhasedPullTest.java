package riid.performance.parrallel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestFilesystemSupport;
import riid.core.fs.TestPaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WARNING: THIS TEST WILLREMOVE ALL PODMAN CONTAINERS
 * Phased pull load against a real {@link DaemonServer}: repositories from
 * {@code PopularDockerImagesSizes.txt} data rows 2–10 (header excluded).
 * Phase A: odd positions (1,3,5,7,9); pause 10s; phase B: even positions (2,4,6,8).
 *
 * <p>Requires Linux, curl, podman, network to Docker Hub.
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
class Daemon9ImagesPhasedPullTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REF = "latest";
    private static final String RUNTIME = "podman";

    /** Rows 2,4,6,8,10 → odd 1-based positions 1,3,5,7,9 within lines 2–10. */
    private static final String[] PHASE_ODD_REPOS = {
            "library/hello-seattle",
            "library/cirros",
            "library/photon",
            "library/eggdrop",
            "library/spiped",
    };

    /** Rows 3,5,7,9 → even 1-based positions 2,4,6,8 within lines 2–10. */
    private static final String[] PHASE_EVEN_REPOS = {
            "library/hola-mundo",
            "library/jobber",
            "library/api-firewall",
            "library/hitch",
    };

    @Test
    void phasedPullsOddThenEvenWithMetricsPrinted() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        removeAllPodmanContainers();

        long testStartNs = System.nanoTime();
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "riid-perf-popular-", ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));
        String registry = TestRegistryConfig.registryName();

        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        CliApplication.ImageLoader loader = (repository, reference, runtimeId) -> {
            try (ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(configPath, null, prom)) {
                return facade.load(
                        ImageId.fromRegistry(registry, repository, reference),
                        runtimeId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load image", e);
            }
        };

        Path workDir = Files.createTempDirectory("riid-perf-popular-pull");
        Path socketPath = workDir.resolve("riid.sock");
        Duration requestTimeout = Duration.ofMinutes(30);
        DaemonServer daemon = new DaemonServer(
                socketPath.toString(),
                "127.0.0.1",
                0,
                loader,
                Set.of(RUNTIME),
                8,
                requestTimeout,
                AppConfig.OverloadPolicy.REJECT,
                prom);

        long lastPullDurationMs = -1L;
        long lastCompletedEpochMs = -1L;
        try {
            daemon.start();
            for (String repo : PHASE_ODD_REPOS) {
                pullOne(socketPath, workDir, repo);
            }
            Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            for (String repo : PHASE_EVEN_REPOS) {
                long opStart = System.nanoTime();
                pullOne(socketPath, workDir, repo);
                lastPullDurationMs = (System.nanoTime() - opStart) / 1_000_000L;
                lastCompletedEpochMs = System.currentTimeMillis();
            }
        } finally {
            daemon.stop();
            TestFilesystemSupport.deleteRecursive(workDir);
            try {
                Files.deleteIfExists(configPath);
            } catch (IOException ignored) {
                // best effort
            }
        }

        long totalMs = (System.nanoTime() - testStartNs) / 1_000_000L;
        Instant lastDone = Instant.ofEpochMilli(lastCompletedEpochMs);

        System.out.println("[DaemonPopularImagesPhasedPullTest] phased pull finished OK");
        System.out.println("[DaemonPopularImagesPhasedPullTest] last_pull_duration_ms=" + lastPullDurationMs);
        System.out.println("[DaemonPopularImagesPhasedPullTest] last_operation_completed_instant=" + lastDone);
        System.out.println("[DaemonPopularImagesPhasedPullTest] last_operation_completed_epoch_ms=" + lastCompletedEpochMs);
        System.out.println("[DaemonPopularImagesPhasedPullTest] total_wall_ms_since_container_cleanup=" + totalMs);

        assertTrue(lastPullDurationMs >= 0);
        assertTrue(lastCompletedEpochMs > 0);
    }

    private static void removeAllPodmanContainers() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("podman", "rm", "-af")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "podman rm -af failed: " + out);
    }

    private static void pullOne(Path socketPath, Path workDir, String repository) throws Exception {
        Path bodyFile = workDir.resolve("body-" + repository.replace('/', '-') + ".json");
        String json = "{\"repository\":\"" + repository + "\",\"reference\":\"" + REF
                + "\",\"runtimeId\":\"" + RUNTIME + "\"}";
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

    private static int requestTimeoutSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofMinutes(30).toSeconds());
    }
}
