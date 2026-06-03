package riid.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared cold-cache setup for performance scenarios: Podman storage prune and optional wipe of
 * {@link #ENV_RIID_PERF_CACHE_DIR} (when the env var is set to an existing directory).
 */
public final class PerformanceColdCacheHelper {
    private static final Duration DAEMON_START_TIMEOUT = Duration.ofSeconds(60);

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

    /**
     * Restarts RIID daemon and waits until the unix socket responds to HTTP.
     */
    public static void restartRiidDaemon(Path daemonSocketPath) throws Exception {
        runIgnoreExit("pkill", "-f", "[r]iid.jar.*--daemon");
        if (daemonSocketPath != null) {
            Files.deleteIfExists(daemonSocketPath);
        }
        runOrFail("bash", "-lc", daemonStartCommand());
        waitDaemonReady(daemonSocketPath);
    }

    private static void waitDaemonReady(Path daemonSocketPath) throws Exception {
        assertTrue(daemonSocketPath != null, "daemon socket path must not be null");
        long deadline = System.nanoTime() + DAEMON_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(daemonSocketPath)) {
                Process p = new ProcessBuilder("curl", "-sS", "--max-time", "2", "--unix-socket",
                        daemonSocketPath.toString(), "-o", "/dev/null", "-w", "%{http_code}", "-X", "GET",
                        "http://localhost/pull").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor() == 0 && ("405".equals(out) || "400".equals(out) || "404".equals(out))) {
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("RIID daemon did not become ready on socket: " + daemonSocketPath);
    }

    private static String daemonStartCommand() {
        return """
                set -a; [ -f config/.env ] && . config/.env; set +a
                if [ -n "$DOCKERHUB_USER" ] && [ -n "$DOCKERHUB_TOKEN" ]; then
                  nohup stdbuf -oL -eL java -Driid.dev.dirtyRegistryLogs=true -jar build/libs/riid.jar --daemon --config ./config/config.yaml --username "$DOCKERHUB_USER" --password-env DOCKERHUB_TOKEN >> daemonLogs.txt 2>&1 &
                else
                  nohup stdbuf -oL -eL java -Driid.dev.dirtyRegistryLogs=true -jar build/libs/riid.jar --daemon --config ./config/config.yaml >> daemonLogs.txt 2>&1 &
                fi
                """;
    }

    private static void runIgnoreExit(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    private static void runOrFail(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }
}
