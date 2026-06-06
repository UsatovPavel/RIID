package riid.performance.parrallel.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.config.PopularDockerHubImagesFromProgramDocs;
import riid.core.config.TestConfigYaml;
import riid.core.fs.TestFilesystemSupport;
import riid.performance.DaemonUnixSocketPullSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PR15 scenario (c): assume RIID daemon already holds layers for the fixture list (warm cache).
 * Implementation: sequential {@code POST /pull} for all
 * {@link PopularDockerHubImagesFromProgramDocs#SCENARIO_C_WARM_REPOSITORIES} (subset of the PR15 thirty:
 * excludes {@code library/clefos}, which commonly mismatches amd64 manifests on Docker Hub), then
 * {@code podman system prune -af}
 * only (RIID cache unchanged), then the same {@code POST /pull} requests in parallel ({@code runtimeId: podman}).
 *
 * <p>Requires external daemon, {@link TestConfigYaml#resolveDaemonUnixSocketPath()}, Linux, curl, podman,
 * network; {@code app.daemon.maxConcurrentPulls} should accommodate concurrent pulls for this fixture size.
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
class Daemon30ImagesWarmRiidPodmanPruneConcurrentPullTest {

    private static final String RUNTIME = "podman";
    private static final int N = PopularDockerHubImagesFromProgramDocs.SCENARIO_C_WARM_REPOSITORIES.size();

    @Test
    void sequentialWarmRiidThenPrunePodmanThenConcurrentPulls() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");
        assumeTrue(commandAvailable("podman"), "podman must be on PATH");

        Path socketPath = TestConfigYaml.resolveDaemonUnixSocketPath();
        assumeTrue(Files.exists(socketPath), "daemon socket must exist: " + socketPath);

        List<String> repos = PopularDockerHubImagesFromProgramDocs.SCENARIO_C_WARM_REPOSITORIES;
        String ref = PopularDockerHubImagesFromProgramDocs.POPULAR_IMAGES_REFERENCE;

        Path workDir = Files.createTempDirectory("riid-perf-scen-c");
        try {
            long warmupStart = System.nanoTime();
            for (int i = 0; i < N; i++) {
                String repo = repos.get(i);
                long t0 = System.nanoTime();
                DaemonUnixSocketPullSupport.postPull(socketPath, workDir, repo, ref, RUNTIME);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                System.out.println("[Daemon30ImagesWarmRiidPodmanPruneConcurrentPullTest] warmup i=" + (i + 1)
                        + '/' + N + " repo=" + repo + " pull_ms=" + ms);
            }
            long warmupWallMs = (System.nanoTime() - warmupStart) / 1_000_000L;
            System.out.println("[Daemon30ImagesWarmRiidPodmanPruneConcurrentPullTest] warmup_wall_ms=" + warmupWallMs);

            runOrFail("podman", "system", "prune", "-af");

            long[] pullMsByIndex = new long[N];
            CountDownLatch startGate = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Void>> futures = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    final int idx = i;
                    String repo = repos.get(i);
                    futures.add(executor.submit(() -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        long t0 = System.nanoTime();
                        DaemonUnixSocketPullSupport.postPull(socketPath, workDir, repo, ref, RUNTIME);
                        pullMsByIndex[idx] = (System.nanoTime() - t0) / 1_000_000L;
                        return null;
                    }));
                }

                long waveStart = System.nanoTime();
                startGate.countDown();
                for (Future<Void> f : futures) {
                    f.get();
                }
                long waveWallMs = (System.nanoTime() - waveStart) / 1_000_000L;

                List<Long> pullMsList = new ArrayList<>(N);
                long sumPullMs = 0L;
                for (int i = 0; i < N; i++) {
                    pullMsList.add(pullMsByIndex[i]);
                    sumPullMs += pullMsByIndex[i];
                    System.out.println("[Daemon30ImagesWarmRiidPodmanPruneConcurrentPullTest] concurrent i=" + (i + 1)
                            + '/' + N + " repo=" + repos.get(i) + " pull_ms=" + pullMsByIndex[i]);
                }
                System.out.println("[Daemon30ImagesWarmRiidPodmanPruneConcurrentPullTest] "
                        + "after_podman_prune_riid_pull_ms_list=" + pullMsList);
                System.out.println("[Daemon30ImagesWarmRiidPodmanPruneConcurrentPullTest] "
                        + "after_podman_prune_riid_sum_pull_ms=" + sumPullMs
                        + " concurrent_wave_wall_ms=" + waveWallMs);
            }
        } finally {
            TestFilesystemSupport.deleteRecursive(workDir);
        }
    }

    private static void runOrFail(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }

    private static boolean commandAvailable(String command) {
        try {
            Process proc = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean finished = proc.waitFor(1, TimeUnit.MINUTES);
            return finished && proc.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
