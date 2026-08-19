package riid.runtime.adapter;

import riid.runtime.BoundedCommandExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Podman adapter (WSL2-friendly): {@code podman load -q -i path} for a file;
 * piped layout import uses {@code podman load -q} (stdin is the default input
 * per {@code podman load --help}).
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    public static final String PODMAN_BIN = "podman";
    private static final int MAX_PROC_STDERR = 64 * 1024;

    @Override
    public RuntimeId runtimeId() {
        return RuntimeId.PODMAN;
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        // false: `-i <path>` skips podman load's stdin-only io.Copy(tempfile, stdin)
        // step (cmd/podman/images/load.go) entirely. ~6% faster handoff, confirmed
        // over 4 independent fresh-Dragonfly-install A/B rounds, see bench_log.md.
        return false;
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = List.of(PODMAN_BIN, "load", "-q", "-i", imagePath.toAbsolutePath().toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("podman load failed (exit " + shellResult.exitCode() + "): " + shellResult.stdout()
                    + shellResult.stderr());
        }
    }

    /**
     * Streams {@code tar -cf - -C layout .} into {@code podman load -q} on stdin
     * (no {@code -i -}; that is a bogus path).
     */
    @Override
    public void importOciLayoutDirectory(Path ociLayoutRoot) throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> tarCmd = List.of("tar", "-cf", "-", "-C", root.toString(), ".");
        List<String> loadCmd = List.of(PODMAN_BIN, "load", "-q");
        BoundedCommandExecution.PipedShellResult result = BoundedCommandExecution.runWithStdoutPipedToStdin(tarCmd,
                loadCmd, MAX_PROC_STDERR, this::startProcess);
        result.throwIfFailed("tar", "podman load");
    }

    /**
     * Hook for tests to override process creation.
     */
    protected Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }

    protected BoundedCommandExecution.ShellResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        return BoundedCommandExecution.run(command);
    }
}
