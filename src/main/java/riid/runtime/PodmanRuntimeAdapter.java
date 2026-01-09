package riid.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Placeholder Podman adapter (WSL2).
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    @Override
    public String runtimeId() {
        return "podman";
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Podman adapter not implemented");
    }
}


