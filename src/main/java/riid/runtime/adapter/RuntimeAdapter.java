package riid.runtime.adapter;

import java.io.IOException;
import java.nio.file.Path;

import riid.core.model.manifest.Manifest;

/**
 * Adapter for a specific container runtime.
 */
public interface RuntimeAdapter {
    /**
     * @return runtime id, e.g. {@link RuntimeId#PODMAN},
     *         {@link RuntimeId#CONTAINERD}.
     */
    RuntimeId runtimeId();

    /**
     * Can this adapter handle the given runtime id?
     */
    default boolean supports(String runtimeId) {
        return runtimeId().value().equalsIgnoreCase(runtimeId);
    }

    /**
     * Import/downloaded image (OCI layout or tar) into runtime.
     */
    void importImage(Path imagePath) throws IOException, InterruptedException;

    /**
     * When {@code true}, the app layer builds an on-disk OCI layout only and calls
     * {@link #importOciLayoutDirectory(Path)} so the runtime can stream a tar (e.g.
     * {@code tar cf -} to stdin) instead of materializing an oci-archive file.
     * Default {@code false}.
     */
    default boolean prefersOciLayoutStreamImport() {
        return false;
    }

    /**
     * Import image from a directory that follows OCI image layout (blobs,
     * index.json, oci-layout). Used only when
     * {@link #prefersOciLayoutStreamImport()} is {@code true}.
     */
    default void importOciLayoutDirectory(Path ociLayoutRoot) throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "Runtime " + runtimeId() + " does not support OCI layout directory import");
    }

    /**
     * Can this runtime take the layers of <em>this</em> image one at a time, while
     * the rest of it is still downloading? Answered per manifest because an adapter
     * may support the incremental path only for some images (e.g. Porto falls back
     * to a flattened import once the layer chain no longer fits its metadata).
     *
     * <p>
     * When {@code true}, the app layer drives
     * {@link #beginIncrementalImport(String, Manifest)} instead of building a whole
     * archive/layout first. Default {@code false}.
     */
    default boolean supportsIncrementalImport(Manifest manifest) {
        return false;
    }

    /**
     * Opens an incremental import session for one image. Used only when
     * {@link #supportsIncrementalImport(Manifest)} is {@code true}.
     *
     * @param imageName
     *            name the finished image gets in the runtime
     * @param manifest
     *            image manifest; layers are fed in its order
     */
    default IncrementalImageImport beginIncrementalImport(String imageName, Manifest manifest)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "Runtime " + runtimeId() + " does not support incremental image import");
    }
}
