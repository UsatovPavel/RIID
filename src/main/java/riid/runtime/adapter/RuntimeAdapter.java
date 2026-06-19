package riid.runtime.adapter;

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

    /**
     * When {@code true}, the app layer builds an on-disk OCI layout only and calls
     * {@link #importOciLayoutDirectory(Path, String)} instead of materializing an
     * oci-archive file. Default {@code false}.
     */
    default boolean prefersOciLayoutStreamImport() {
        return false;
    }

    /**
     * Import image from a directory that follows OCI image layout (blobs,
     * index.json, oci-layout). Used only when
     * {@link #prefersOciLayoutStreamImport()} is {@code true}.
     *
     * @param reference
     *            the {@code org.opencontainers.image.ref.name} annotation value
     *            written into {@code index.json} (e.g. {@code
     *                  "name:tag"}), used to select/name the image being imported.
     */
    default void importOciLayoutDirectory(Path ociLayoutRoot, String reference)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "Runtime " + runtimeId() + " does not support OCI layout directory import");
    }
}
