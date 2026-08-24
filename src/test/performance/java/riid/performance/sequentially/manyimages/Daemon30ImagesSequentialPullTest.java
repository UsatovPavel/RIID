package riid.performance.sequentially.manyimages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.config.PopularDockerHubImagesFromProgramDocs;
import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;
import riid.performance.DaemonUnixSocketPullSupport;
import riid.performance.PerformanceColdCacheHelper;
import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PR15 scenario b1 (moderate): same first 30 repositories as
 * {@link PopularDockerHubImagesFromProgramDocs#FIRST_30_REPOSITORIES},
 * sequential pulls. Перед фазой (1): {@code podman system prune -af} и restart
 * RIID daemon (cold daemon process). Далее: (1) via an already running RIID
 * daemon ({@code POST /pull}, {@code runtimeId: podman}), (2) then native
 * {@code podman pull} for each, after {@code podman system prune -af}
 * immediately before that Podman phase
 * ({@link #coldPodmanCacheThenMeasuredPulls()}).
 *
 * <p>
 * {@link #podmanPhaseOnly()} — только шаг (2), без демона.
 *
 * <p>
 * Daemon socket: {@link TestConfigYaml#resolveDaemonUnixSocketPath()} для
 * полного сценария.
 *
 * <p>
 * Prints per-phase pull duration lists, sums, and wall times. A failed pull
 * (daemon or podman) is logged and skipped; the test does not fail on
 * individual image errors.
 */
@EnabledOnOs(OS.LINUX)
@Tag("performance")
@Tag("filesystem")
class Daemon30ImagesSequentialPullTest {

    private static final String REPO_TAG = " repo=";
    private static final String DOCKER_HUB_REGISTRY = "registry-1.docker.io";
    private static final String RUNTIME = "podman";
    private static final int N = PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES.size();
    private static final int CLEFOS_INDEX = 17;

    @Test
    void daemonThenPodman() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path socketPath = TestConfigYaml.resolveDaemonUnixSocketPath();
        assumeTrue(commandAvailable("podman"), "podman must be on PATH");
        PerformanceColdCacheHelper.clearPodmanCaches();
        PerformanceColdCacheHelper.restartRiidDaemon(socketPath);
        assumeTrue(Files.exists(socketPath), "daemon socket must exist after restart: " + socketPath);

        long testStartNs = System.nanoTime();

        List<Long> riidPullMsList = new ArrayList<>(N);
        Path workDir = Files.createTempDirectory("riid-perf-b1-seq");
        try {
            int index = 0;
            for (String repo : PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES) {
                index++;
                long t0 = System.nanoTime();
                try {
                    DaemonUnixSocketPullSupport.PullResult result = DaemonUnixSocketPullSupport.postPullCapture(
                            socketPath, workDir, repo, PopularDockerHubImagesFromProgramDocs.POPULAR_IMAGES_REFERENCE,
                            RUNTIME);
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    if (result.finished() && result.exitCode() == 0 && result.httpStatus() == 200) {
                        riidPullMsList.add(ms);
                        System.out.println("[Daemon30ImagesSequentialPullTest] riid i=" + index + '/' + N + REPO_TAG
                                + repo + " pull_ms=" + ms);
                    } else {
                        String bodyOneLine = result.body().replace('\n', ' ').trim();
                        System.err.println("[Daemon30ImagesSequentialPullTest] riid FAILED i=" + index + '/' + N
                                + REPO_TAG + repo + " exit=" + result.exitCode() + " http=" + result.httpStatus()
                                + " pull_wall_ms=" + ms + " stderr=" + result.stderr() + " body=" + bodyOneLine);
                        if (index == CLEFOS_INDEX) {
                            System.err
                                    .println("[Daemon30ImagesSequentialPullTest] i=17 expected non-200 candidate: http="
                                            + result.httpStatus() + " exit=" + result.exitCode());
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Daemon30ImagesSequentialPullTest] riid FAILED i=" + index + '/' + N + REPO_TAG
                            + repo + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        } finally {
            TestFilesystemSupport.deleteRecursive(workDir);
        }

        long riidPhaseWallMs = (System.nanoTime() - testStartNs) / 1_000_000L;
        long riidSumPullMs = sum(riidPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] riid_pull_ms_list=" + riidPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] riid_sum_pull_ms=" + riidSumPullMs
                + " riid_phase_wall_ms=" + riidPhaseWallMs);

        long podmanPhaseStart = System.nanoTime();
        List<Long> podmanPullMsList = coldPodmanCacheThenMeasuredPulls();
        long podmanPhaseWallMs = (System.nanoTime() - podmanPhaseStart) / 1_000_000L;
        long podmanSumPullMs = sum(podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_pull_ms_list=" + podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_sum_pull_ms=" + podmanSumPullMs
                + " podman_phase_wall_ms=" + podmanPhaseWallMs);

        long totalWallMs = (System.nanoTime() - testStartNs) / 1_000_000L;
        System.out.println("[Daemon30ImagesSequentialPullTest] total_wall_ms=" + totalWallMs + " riid_ok="
                + riidPullMsList.size() + '/' + N + " podman_ok=" + podmanPullMsList.size() + '/' + N);
    }

    /**
     * Только шаг (2) b1: такой же старт, как у полного сценария —
     * {@link PerformanceColdCacheHelper#clearPodmanCaches()}, затем
     * {@link #coldPodmanCacheThenMeasuredPulls()}. UDS/демон не нужны.
     */
    @Test
    void podmanPhaseOnly() throws Exception {
        assumeTrue(commandAvailable("podman"), "podman must be on PATH");
        long testStartNs = System.nanoTime();
        PerformanceColdCacheHelper.clearPodmanCaches();
        long podmanPhaseStart = System.nanoTime();
        List<Long> podmanPullMsList = coldPodmanCacheThenMeasuredPulls();
        long podmanPhaseWallMs = (System.nanoTime() - podmanPhaseStart) / 1_000_000L;
        long podmanSumPullMs = sum(podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_pull_ms_list=" + podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_sum_pull_ms=" + podmanSumPullMs
                + " podman_phase_wall_ms=" + podmanPhaseWallMs);
        long totalWallMs = (System.nanoTime() - testStartNs) / 1_000_000L;
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_only_total_wall_ms=" + totalWallMs + " podman_ok="
                + podmanPullMsList.size() + '/' + N);
    }

    /**
     * {@code podman system prune -af}, затем 30× измеренный {@code podman pull}
     * (фаза Podman для b1).
     */
    private static List<Long> coldPodmanCacheThenMeasuredPulls() throws Exception {
        PerformanceColdCacheHelper.clearPodmanCaches();
        return measuredPodmanPulls();
    }

    /**
     * 30 подряд измеренных {@code podman pull} (без предварительного prune).
     */
    private static List<Long> measuredPodmanPulls() throws Exception {
        List<Long> podmanPullMsList = new ArrayList<>();
        String ref = PopularDockerHubImagesFromProgramDocs.POPULAR_IMAGES_REFERENCE;
        int index = 0;
        for (String repo : PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES) {
            index++;
            String podmanRef = podmanImageReference(repo, ref);
            long t0 = System.nanoTime();
            ProcessResult r = runProcess("podman", "pull", podmanRef);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (r.exitCode() != 0) {
                System.err.println("[Daemon30ImagesSequentialPullTest] podman FAILED i=" + index + '/' + N + REPO_TAG
                        + repo + " ref=" + podmanRef + " exit=" + r.exitCode() + " pull_wall_ms=" + ms + "\n"
                        + r.output());
                continue;
            }
            podmanPullMsList.add(ms);
            System.out.println("[Daemon30ImagesSequentialPullTest] podman i=" + index + '/' + N + REPO_TAG + repo
                    + " pull_ms=" + ms + " ref=" + podmanRef);
        }
        return podmanPullMsList;
    }

    private static long sum(List<Long> values) {
        long s = 0;
        for (Long v : values) {
            s += v;
        }
        return s;
    }

    private static String podmanImageReference(String repository, String reference) {
        String reg = TestRegistryConfig.registryName();
        if (DOCKER_HUB_REGISTRY.equals(reg)) {
            return "docker.io/" + repository + ":" + reference;
        }
        int port = TestRegistryConfig.port();
        if (port > 0) {
            return reg + "/" + repository + ":" + reference;
        }
        return reg + "/" + repository + ":" + reference;
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static ProcessResult runProcess(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        return new ProcessResult(code, out);
    }

    private static void runOrFail(String... command) throws Exception {
        ProcessResult r = runProcess(command);
        assertEquals(0, r.exitCode(), "Command failed: " + String.join(" ", command) + "\n" + r.output());
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
