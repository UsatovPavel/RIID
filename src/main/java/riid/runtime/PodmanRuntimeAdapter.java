package riid.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();
        String output;
        try (var in = p.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        int code = p.waitFor();
        if (code != 0) {
            String err;
            try (var es = p.getErrorStream()) {
                err = new String(es.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IOException("podman load failed (exit " + code + "): " + output + err);
        }
    }
}


