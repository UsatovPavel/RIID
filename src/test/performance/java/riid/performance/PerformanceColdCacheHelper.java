package riid.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared cold-cache setup for performance scenarios: Podman storage prune and optional wipe of
 * {@link #ENV_RIID_PERF_CACHE_DIR} (when the env var is set to an existing directory).
 */
public final class PerformanceColdCacheHelper {

    /**
     * Directory to delete when set — daemon under test should use this as cache/temp root for cold iterations.
     */
    public static final String ENV_RIID_PERF_CACHE_DIR = "RIID_PERF_CACHE_DIR";

    private PerformanceColdCacheHelper() {
    }

    public static boolean podmanAvailable() {
        try {
            Process p = new ProcessBuilder("podman", "--version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(1, TimeUnit.MINUTES);
            return finished && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Runs {@code podman system prune -af}; fails the test if the command exits non-zero. */
    public static void clearPodmanCaches() throws Exception {
        runOrFail("podman", "system", "prune", "-af");
    }

    /** If {@link #ENV_RIID_PERF_CACHE_DIR} points at a directory, deletes it recursively. */
    public static void clearRiidCacheDirIfSet() throws Exception {
        String cacheDir = System.getenv(ENV_RIID_PERF_CACHE_DIR);
        if (cacheDir != null && !cacheDir.isBlank()) {
            Path p = Path.of(cacheDir);
            if (Files.isDirectory(p)) {
                TestFilesystemSupport.deleteRecursive(p);
            }
        }
    }

    /**
     * Полная очистка перед perf-сценарием: {@code podman system prune -af}, затем каталог
     * Т.к. в будущем будет протестирован docker возможно это НЕ то же самое что clearPodmanAndRiidCaches
     * {@link #ENV_RIID_PERF_CACHE_DIR} если задан. Один вызов — весь «холодный» старт кэшей.
     */
    public static void clearAllCache() throws Exception {
        clearPodmanCaches();
        clearRiidCacheDirIfSet();
    }

    /** То же, что {@link #clearAllCache()}. */
    public static void clearPodmanAndRiidCaches() throws Exception {
        clearPodmanCaches();
        clearRiidCacheDirIfSet();
    }

    private static void runOrFail(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }
}
