package riid.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import riid.runtime.BoundedCommandExecution;

/**
 * Shared helpers for integration tests that touch the real filesystem or external CLI tools.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestFilesystemSupport {

    private TestFilesystemSupport() {
    }

    /**
     * Whether {@code curl} is on {@code PATH} and responds to {@code --version}.
     * Uses {@link BoundedCommandExecution} for bounded stdout/stderr capture (same as runtime adapters).
     */
    public static boolean curlAvailable() {
        try {
            BoundedCommandExecution.ShellResult r = BoundedCommandExecution.run(List.of("curl", "--version"));
            return r.exitCode() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Deletes a file or directory tree (deepest paths first). Best-effort: ignores per-path failures.
     */
    public static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
