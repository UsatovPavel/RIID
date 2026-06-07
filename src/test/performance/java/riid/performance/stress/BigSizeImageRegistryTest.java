package riid.performance.stress;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;
import riid.core.fs.TestFilesystemSupport;
import riid.performance.DaemonUnixSocketPullSupport;
import riid.performance.PerformanceColdCacheHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stress: {@code POST /pull} for a large public image (~1.7 GiB aggregate per
 * {@code PopularDockerImagesSizes.txt}) through an already running RIID daemon
 * (registry path inside Riid), same wire format as production clients; then
 * {@code podman system prune -af} and a measured native {@code podman pull} of
 * the same ref (cold podman store, comparable to PR15 scenario (a)).
 *
 * <p>
 * Requires Linux, {@code curl}, {@code podman}, network, daemon on
 * {@link TestConfigYaml#resolveDaemonUnixSocketPath()}.
 *
 * <pre>
 *   ./gradlew performanceTest --tests 'riid.performance.stress.BigSizeImageRegistryTest'
 *   ./gradlew testStress --tests 'riid.performance.stress.BigSizeImageRegistryTest'
 * </pre>
 */
@EnabledOnOs(OS.LINUX)
@Tag("stress")
@Tag("local")
@Tag("filesystem")
class BigSizeImageRegistryTest {

    /**
     * @see PopularDockerImagesSizes.txt — {@code library/silverpeas} latest ~1.7
     *      GiB
     */
    private static final String REPOSITORY = "library/silverpeas";
    private static final String REFERENCE = "latest";
    private static final String RUNTIME = "podman";

    @Test
    void pullLargeImageViaDaemon() throws Exception {
        assumeTrue(TestFilesystemSupport.curlAvailable(), "curl must be on PATH for HTTP over UDS");
        assumeTrue(PerformanceColdCacheHelper.podmanAvailable(), "podman must be on PATH");

        Path socketPath = TestConfigYaml.resolveDaemonUnixSocketPath();
        assumeTrue(Files.exists(socketPath), "daemon socket must exist: " + socketPath);

        PerformanceColdCacheHelper.clearAllCache();

        Path workDir = Files.createTempDirectory("riid-stress-big-image");
        try {
            long t0 = System.nanoTime();
            DaemonUnixSocketPullSupport.postPull(socketPath, workDir, REPOSITORY, REFERENCE, RUNTIME);
            long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(wallMs > 0, "wall time should be positive");
            System.out.println("[BigSizeImageRegistryTest] riid_daemon repository=" + REPOSITORY + ":" + REFERENCE
                    + " pull_wall_ms=" + wallMs);

            PerformanceColdCacheHelper.clearPodmanCaches();
            String podmanRef = podmanImageReference(REPOSITORY, REFERENCE);
            long p0 = System.nanoTime();
            runOrFail("podman", "pull", podmanRef);
            long podmanMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - p0);
            assertTrue(podmanMs > 0, "podman pull wall time should be positive");
            System.out
                    .println("[BigSizeImageRegistryTest] podman_native pull_wall_ms=" + podmanMs + " ref=" + podmanRef);
        } finally {
            TestFilesystemSupport.deleteRecursive(workDir);
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

    private static void runOrFail(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }
}
