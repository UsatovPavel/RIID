package riid.performance.sequentially.oneimage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;
import riid.core.fs.TestFilesystemSupport;
import riid.performance.DaemonUnixSocketPullSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PR15 scenario (a): ~50 MiB image, {@link #ITERATIONS} cold-cache rounds for Podman — timings for
 * {@code POST /pull} via an already running RIID daemon, and separate rounds for native
 * {@code podman pull} of the same OCI ref.
 *
 * <p>Per iteration (RIID block): {@code podman system prune -af}, optional wipe of
 * {@code RIID_PERF_CACHE_DIR}, then measuredaemon pull time → appended to {@code riid_pull_ms}.
 * Then Podman block: same iterations with prune before each {@code podman pull} →
 * {@code podman_pull_ms}.
 *
 * <p>Daemon socket: {@link TestConfigYaml#resolveDaemonUnixSocketPath()}. Requires Linux, curl, podman,
 * network. Daemon must accept {@code runtimeId: podman}.
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
class DaemonBenchmarkColdCachePullsTest {

    /** Directory to delete between RIID iterations when set (daemon should use this as cache/temp). */
    public static final String ENV_RIID_PERF_CACHE_DIR = "RIID_PERF_CACHE_DIR";

    private static final String RUNTIME = "podman";
    /** ~50 MiB tier from {@code PopularDockerImagesSizes.txt} ({@code library/irssi}). */
    private static final String REPOSITORY = "library/irssi";
    private static final String REFERENCE = "latest";
    private static final int ITERATIONS = 5;

    @Test
    void fiveColdIterationsRiidThenPodmanNativeTimingsInLists() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");
        assumeTrue(commandAvailable("podman"), "podman must be on PATH");

        Path socketPath = TestConfigYaml.resolveDaemonUnixSocketPath();
        assumeTrue(Files.exists(socketPath), "daemon socket must exist: " + socketPath);

        Path workDir = Files.createTempDirectory("riid-perf-scenario-a");
        try {
            List<Long> riidPullMs = new ArrayList<>(ITERATIONS);
            long riidPhaseStart = System.nanoTime();
            for (int i = 1; i <= ITERATIONS; i++) {
                coldCachesForRiidIteration();
                long t0 = System.nanoTime();
                DaemonUnixSocketPullSupport.postPull(socketPath, workDir, REPOSITORY, REFERENCE, RUNTIME);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                riidPullMs.add(ms);
                System.out.println("[DaemonScenarioAPodmanColdCachePullsTest] riid i=" + i + "/" + ITERATIONS + " pull_ms=" + ms);
            }
            long riidPhaseWallMs = (System.nanoTime() - riidPhaseStart) / 1_000_000L;

            List<Long> podmanPullMs = new ArrayList<>(ITERATIONS);
            String podmanRef = podmanImageReference(REPOSITORY, REFERENCE);
            long podmanPhaseStart = System.nanoTime();
            for (int i = 1; i <= ITERATIONS; i++) {
                runOrFail("podman", "system", "prune", "-af");
                long t0 = System.nanoTime();
                runOrFail("podman", "pull", podmanRef);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                podmanPullMs.add(ms);
                System.out.println("[DaemonScenarioAPodmanColdCachePullsTest] podman i=" + i + "/" + ITERATIONS
                        + " pull_ms=" + ms + " ref=" + podmanRef);
            }
            long podmanPhaseWallMs = (System.nanoTime() - podmanPhaseStart) / 1_000_000L;

            long sumRiid = sum(riidPullMs);
            long sumPodman = sum(podmanPullMs);
            System.out.println("[DaemonScenarioAPodmanColdCachePullsTest] riid_pull_ms_list=" + riidPullMs);
            System.out.println("[DaemonScenarioAPodmanColdCachePullsTest] riid_sum_pull_ms=" + sumRiid
                    + " riid_phase_wall_ms=" + riidPhaseWallMs + " riid_median_pull_ms=" + median(riidPullMs));
            System.out.println("[DaemonScenarioAPodmanColdCachePullsTest] podman_pull_ms_list=" + podmanPullMs);
            System.out.println("[DaemonScenarioAPodmanColdCachePullsTest] podman_sum_pull_ms=" + sumPodman
                    + " podman_phase_wall_ms=" + podmanPhaseWallMs + " podman_median_pull_ms=" + median(podmanPullMs));

            assertEquals(ITERATIONS, riidPullMs.size());
            assertEquals(ITERATIONS, podmanPullMs.size());
        } finally {
            TestFilesystemSupport.deleteRecursive(workDir);
        }
    }

    private static void coldCachesForRiidIteration() throws Exception {
        runOrFail("podman", "system", "prune", "-af");
        String cacheDir = System.getenv(ENV_RIID_PERF_CACHE_DIR);
        if (cacheDir != null && !cacheDir.isBlank()) {
            Path p = Path.of(cacheDir);
            if (Files.isDirectory(p)) {
                TestFilesystemSupport.deleteRecursive(p);
            }
        }
    }

    private static String podmanImageReference(String repository, String reference) {
        String reg = TestRegistryConfig.registryName();
        if ("registry-1.docker.io".equals(reg)) {
            return "docker.io/" + repository + ":" + reference;
        }
        int port = TestRegistryConfig.port();
        if (port > 0) {
            return reg + "/" + repository + ":" + reference;
        }
        return reg + "/" + repository + ":" + reference;
    }

    private static long sum(List<Long> values) {
        long s = 0;
        for (Long v : values) {
            s += v;
        }
        return s;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
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
            boolean finished = p.waitFor(1, TimeUnit.MINUTES);
            return finished && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
