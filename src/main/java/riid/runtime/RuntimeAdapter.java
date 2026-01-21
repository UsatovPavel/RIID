package riid.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Adapter for a specific container runtime.
 */
public interface RuntimeAdapter {
    /**
     * @return runtime id, e.g. "podman", "porto", "containerd".
     */
    String runtimeId();

    /**
     * Can this adapter handle the given runtime id?
     */
    default boolean supports(String runtimeId) {
        return runtimeId().equalsIgnoreCase(runtimeId);
    }

    /**
     * Import/downloaded image (OCI layout or tar) into runtime.
     */
    void importImage(Path imagePath) throws IOException, InterruptedException;
}


