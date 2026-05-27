package riid.performance.parrallel.network;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.config.PopularDockerHubImagesFromProgramDocs;
import riid.core.config.TestConfigYaml;
import riid.core.fs.TestFilesystemSupport;
import riid.performance.DaemonUnixSocketPullSupport;
import riid.performance.PerformanceColdCacheHelper;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PR15-style load: all
 * {@link PopularDockerHubImagesFromProgramDocs#FIRST_30_REPOSITORIES}
 * {@code POST /pull} requests are issued <strong>at the same time</strong>
 * against an already running RIID daemon ({@code runtimeId: podman}). Uses a
 * {@link CountDownLatch} so worker threads start their {@code curl} together
 * after the latch opens.
 *
 * <p>
 * Daemon socket: {@link TestConfigYaml#resolveDaemonUnixSocketPath()} (env
 * overrides documented there). Requires Linux, curl, network; daemon must allow
 * concurrent pulls (see {@code app.daemon.maxConcurrentPulls}).
 */
@EnabledOnOs(OS.LINUX)
@Tag("performance")
@Tag("filesystem")
class Daemon30ImagesConcurrentRiidPullTest {

    private static final String RUNTIME = "podman";
    private static final int N = PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES.size();

    @Test
    void thirtyConcurrentRiidPulls() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path socketPath = TestConfigYaml.resolveDaemonUnixSocketPath();
        assumeTrue(Files.exists(socketPath), "daemon socket must exist: " + socketPath);

        assumeTrue(PerformanceColdCacheHelper.podmanAvailable(), "podman must be on PATH");

        Path workDir = Files.createTempDirectory("riid-perf-b2-concurrent");
        try {
            PerformanceColdCacheHelper.clearAllCache(socketPath, workDir);
            List<String> repos = PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES;
            String ref = PopularDockerHubImagesFromProgramDocs.POPULAR_IMAGES_REFERENCE;

            long[] pullMsByIndex = new long[N];
            CountDownLatch startGate = new CountDownLatch(1);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Void>> futures = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    final int idx = i;
                    String repo = repos.get(i);
                    futures.add(executor.submit(() -> {
                        startGate.await();
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
                    System.out.println("[Daemon30ImagesConcurrentRiidPullTest] riid concurrent i=" + (i + 1) + '/' + N
                            + " repo=" + repos.get(i) + " pull_ms=" + pullMsByIndex[i]);
                }
                System.out.println("[Daemon30ImagesConcurrentRiidPullTest] riid_pull_ms_list=" + pullMsList);
                System.out.println("[Daemon30ImagesConcurrentRiidPullTest] riid_sum_pull_ms=" + sumPullMs
                        + " concurrent_wave_wall_ms=" + waveWallMs);
            }
        } finally {
            TestFilesystemSupport.deleteRecursive(workDir);
        }
    }
}
