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
    public String runtimeId() {
        return "podman";
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        return true;
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
     * AGENT-73 spike: {@code podman pull oci:<layout>} instead of
     * {@code tar | podman load}, to see what breaks (naive, no ref/tag handling
     * yet).
     */
    @Override
    public void importOciLayoutDirectory(Path ociLayoutRoot) throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> cmd = List.of(PODMAN_BIN, "pull", "-q", "oci:" + root);
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("podman pull oci: failed (exit " + shellResult.exitCode() + "): "
                    + shellResult.stdout() + shellResult.stderr());
        }
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
