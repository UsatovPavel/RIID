package riid.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Porto adapter using portoctl CLI to import an OCI archive.
 * Requires portoctl available and portod socket (/run/portod.socket) accessible.
 */
public class PortoRuntimeAdapter implements RuntimeAdapter {
    private static final String PORTOCTL_BIN = "portoctl";

    @Override
    public String runtimeId() {
        return "porto";
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = List.of(
                PORTOCTL_BIN,
                "layer",
                "-I",
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
            throw new IOException("portoctl layer import failed (exit " + code + "): " + output + err);
        }
    }

    /**
     * Hook for tests to override process creation.
     */
    protected Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }
}


