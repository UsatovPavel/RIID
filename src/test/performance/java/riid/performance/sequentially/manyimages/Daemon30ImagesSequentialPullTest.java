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
import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PR15 scenario b1 (moderate): same first 30 repositories as
 * {@link PopularDockerHubImagesFromProgramDocs#FIRST_30_REPOSITORIES}, sequential pulls,
 * (1) via an already running RIID daemon ({@code POST /pull}, {@code runtimeId: podman}),
 * (2) then native {@code podman pull} for each, after a single {@code podman system prune -af}
 * at the start of the Podman phase.
 *
 * <p>{@link #podmanPhaseOnly()} — только шаг (2), без демона.
 *
 * <p>Daemon socket: {@link TestConfigYaml#resolveDaemonUnixSocketPath()} для полного сценария.
 *
 * <p>Prints per-phase pull duration lists, sums, and wall times. A failed pull (daemon or podman) is logged
 * and skipped; the test does not fail on individual image errors.
 */
@EnabledOnOs(OS.LINUX)
@Tag("local")
@Tag("filesystem")
class Daemon30ImagesSequentialPullTest {

    private static final String RUNTIME = "podman";
    private static final int N = PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES.size();

    @Test
    void daemonThenPodman() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");

        Path socketPath = TestConfigYaml.resolveDaemonUnixSocketPath();
        assumeTrue(Files.exists(socketPath), "daemon socket must exist: " + socketPath);

        long testStartNs = System.nanoTime();

        List<Long> riidPullMsList = new ArrayList<>(N);
        Path workDir = Files.createTempDirectory("riid-perf-b1-seq");
        try {
            int index = 0;
            for (String repo : PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES) {
                index++;
                long t0 = System.nanoTime();
                try {
                    DaemonUnixSocketPullSupport.postPull(
                            socketPath,
                            workDir,
                            repo,
                            PopularDockerHubImagesFromProgramDocs.POPULAR_IMAGES_REFERENCE,
                            RUNTIME);
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    riidPullMsList.add(ms);
                    System.out.println("[Daemon30ImagesSequentialPullTest] riid i=" + index + '/'
                            + N + " repo=" + repo + " pull_ms=" + ms);
                } catch (Throwable e) {
                    System.err.println("[Daemon30ImagesSequentialPullTest] riid FAILED i=" + index + '/'
                            + N + " repo=" + repo + ": " + e.getMessage());
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

        assumeTrue(commandAvailable("podman"), "podman must be on PATH");
        runOrFail("podman", "system", "prune", "-af");

        long podmanPhaseStart = System.nanoTime();
        List<Long> podmanPullMsList = measuredPodmanPulls();
        long podmanPhaseWallMs = (System.nanoTime() - podmanPhaseStart) / 1_000_000L;
        long podmanSumPullMs = sum(podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_pull_ms_list=" + podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_sum_pull_ms=" + podmanSumPullMs
                + " podman_phase_wall_ms=" + podmanPhaseWallMs);

        long totalWallMs = (System.nanoTime() - testStartNs) / 1_000_000L;
        System.out.println("[Daemon30ImagesSequentialPullTest] total_wall_ms=" + totalWallMs
                + " riid_ok=" + riidPullMsList.size() + '/' + N
                + " podman_ok=" + podmanPullMsList.size() + '/' + N);
    }

    /** Только {@code podman system prune -af} + 30× {@code podman pull} (как фаза b1); UDS/демон не нужны. */
    @Test
    void podmanPhaseOnly() throws Exception {
        assumeTrue(commandAvailable("podman"), "podman must be on PATH");
        long testStartNs = System.nanoTime();
        runOrFail("podman", "system", "prune", "-af");
        long podmanPhaseStart = System.nanoTime();
        List<Long> podmanPullMsList = measuredPodmanPulls();
        long podmanPhaseWallMs = (System.nanoTime() - podmanPhaseStart) / 1_000_000L;
        long podmanSumPullMs = sum(podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_pull_ms_list=" + podmanPullMsList);
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_sum_pull_ms=" + podmanSumPullMs
                + " podman_phase_wall_ms=" + podmanPhaseWallMs);
        long totalWallMs = (System.nanoTime() - testStartNs) / 1_000_000L;
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_only_total_wall_ms=" + totalWallMs
                + " podman_ok=" + podmanPullMsList.size() + '/' + N);
    }

    /** После {@code podman system prune -af} снаружи — измеренные длительности 30 подряд {@code podman pull}. */
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
                System.err.println("[Daemon30ImagesSequentialPullTest] podman FAILED i=" + index + '/' + N
                        + " repo=" + repo + " ref=" + podmanRef + " exit=" + r.exitCode() + " pull_wall_ms=" + ms
                        + "\n" + r.output());
                continue;
            }
            podmanPullMsList.add(ms);
            System.out.println("[Daemon30ImagesSequentialPullTest] podman i=" + index + '/' + N
                    + " repo=" + repo + " pull_ms=" + ms + " ref=" + podmanRef);
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
        if ("registry-1.docker.io".equals(reg)) {
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
