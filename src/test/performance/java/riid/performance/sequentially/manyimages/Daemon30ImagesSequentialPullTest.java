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
 * <p>Prints per-phase pull duration lists, sums, and wall times.
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
                + " finished OK count=" + N + " (riid + podman)");
        assertEquals(N, riidPullMsList.size());
        assertEquals(N, podmanPullMsList.size());
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
        System.out.println("[Daemon30ImagesSequentialPullTest] podman_only_total_wall_ms=" + totalWallMs);
        assertEquals(N, podmanPullMsList.size());
    }

    /** После {@code podman system prune -af} снаружи — измеренные длительности 30 подряд {@code podman pull}. */
    private static List<Long> measuredPodmanPulls() throws Exception {
        List<Long> podmanPullMsList = new ArrayList<>(N);
        String ref = PopularDockerHubImagesFromProgramDocs.POPULAR_IMAGES_REFERENCE;
        int index = 0;
        for (String repo : PopularDockerHubImagesFromProgramDocs.FIRST_30_REPOSITORIES) {
            index++;
            String podmanRef = podmanImageReference(repo, ref);
            long t0 = System.nanoTime();
            runOrFail("podman", "pull", podmanRef);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
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
