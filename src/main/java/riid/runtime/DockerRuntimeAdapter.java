package riid.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Docker adapter using CLI `docker load -q -i <tar|oci-archive>`.
 */
public class DockerRuntimeAdapter implements RuntimeAdapter {
    private static final String DOCKER_BIN = "docker";

    @Override
    public String runtimeId() {
        return "docker";
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = List.of(
                DOCKER_BIN,
                "load",
                "-q",
                "-i",
                imagePath.toAbsolutePath().toString()
        );
        Process p = startProcess(cmd);
        String output;
        try (var in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            String err;
            try (var es = p.getErrorStream()) {
                err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IOException("docker load failed (exit " + code + "): " + output + err);
        }
    }

    /**
     * Hook for tests to override process creation.
     */
    protected Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }
}

