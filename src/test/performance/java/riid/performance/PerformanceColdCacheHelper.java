package riid.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import riid.core.fs.TestFilesystemSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared cold-cache setup for performance scenarios: Podman storage prune and
 * wipe of RIID {@code TempFileCacheAdapter} dirs ({@value #RIID_CACHE_DIR_PREFIX}*
 * under {@code java.io.tmpdir}).
 */
public final class PerformanceColdCacheHelper {

    /**
     * Prefix of daemon {@link riid.cache.oci.TempFileCacheAdapter} roots under
     * {@code java.io.tmpdir}.
     */
    static final String RIID_CACHE_DIR_PREFIX = "riid-cache-tmp-";

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

    /**
     * Clears all files inside RIID cache dirs. Keeps the root directory so a
     * running daemon can still write new blobs.
     */
    public static void clearRiidCacheDirs() throws IOException {
        for (Path cacheDir : findRiidCacheDirs()) {
            clearDirectoryContents(cacheDir);
        }
    }

    /** Podman prune + RIID cache wipe. */
    public static void clearAllCache() throws Exception {
        clearPodmanCaches();
        clearRiidCacheDirs();
    }

    /** @deprecated use {@link #clearAllCache()} */
    @Deprecated
    public static void clearPodmanAndRiidCaches() throws Exception {
        clearAllCache();
    }

    static List<Path> findRiidCacheDirs() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.isDirectory(tmp)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(tmp)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(RIID_CACHE_DIR_PREFIX)).toList();
        }
    }

    private static void clearDirectoryContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                TestFilesystemSupport.deleteRecursive(entry);
            }
        }
    }

    private static void runOrFail(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "Command failed: " + String.join(" ", command) + "\n" + out);
    }
}
