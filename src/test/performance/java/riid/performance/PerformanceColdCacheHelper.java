package riid.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared cold-cache setup for performance scenarios.
 */
public final class PerformanceColdCacheHelper {
    private static final Duration DAEMON_START_TIMEOUT = Duration.ofSeconds(60);

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

    public static void clearPodmanCaches() throws Exception {
        runOrFail("podman", "system", "prune", "-af");
    }

    /** Podman prune only (no manual RIID cache directory deletion). */
    public static void clearAllCache() throws Exception {
        clearPodmanCaches();
    }

    /**
     * Restart RIID daemon process and wait until the Unix socket starts
     * responding to HTTP.
     */
    public static void restartRiidDaemon(Path daemonSocketPath) throws Exception {
        runIgnoreExit("pkill", "-f", "[r]iid.jar.*--daemon");
        if (daemonSocketPath != null) {
            Files.deleteIfExists(daemonSocketPath);
        }
        runOrFail("bash", "-lc", daemonStartCommand());
        waitDaemonReady(daemonSocketPath);
    }

    /** @deprecated use {@link #clearPodmanCaches()} + {@link #restartRiidDaemon(Path)} */
    @Deprecated
    public static void clearPodmanAndRiidCaches() throws Exception {
        clearPodmanCaches();
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

    private static void runOrFail(String command, String arg1, String arg2) throws Exception {
        Process p = new ProcessBuilder(command, arg1, arg2).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command, arg1, arg2) + "\n" + out);
    }

    private static void runOrFail(String command, String arg1, String arg2, String arg3) throws Exception {
        Process p = new ProcessBuilder(command, arg1, arg2, arg3).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command, arg1, arg2, arg3) + "\n" + out);
    }

    private static void runOrFail(String... command) throws Exception {
        if (command.length == 3) {
            runOrFail(command[0], command[1], command[2]);
            return;
        }
        if (command.length == 4) {
            runOrFail(command[0], command[1], command[2], command[3]);
            return;
        }
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }
}
