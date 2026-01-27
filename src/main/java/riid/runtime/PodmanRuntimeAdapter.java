package riid.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Podman adapter (WSL2-friendly) using CLI `podman load -q -i <tar|oci-archive>`.
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    private static final String PODMAN_BIN = "podman";

    @Override
    public String runtimeId() {
        return "podman";
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = List.of(
                PODMAN_BIN,
                "load",
                "-q",
                "-i",
                imagePath.toAbsolutePath().toString()
        );
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("podman load failed (exit " + shellResult.exitCode() + "): "
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
        return BoundedCommandExecution.run(command, 64 * 1024, streamThreads);
    }

    private static int streamThreads = BoundedCommandExecution.DEFAULT_STREAM_THREADS;

    public static void setStreamThreads(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("streamThreads must be positive");
        }
        streamThreads = threads;
    }
}


