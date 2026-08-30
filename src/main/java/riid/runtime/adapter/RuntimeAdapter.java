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
     * Prefix import is on unless configured off: measured faster on 10 images of
     * 10.
     */
    boolean PREFIX_IMPORT_ENABLED_BY_DEFAULT = true;

    /**
     * Can this runtime take <em>this</em> image's layers one at a time, while the
     * rest still downloads? Per manifest, because an adapter may accept only some
     * images (Porto falls back to a flattened import for a long layer chain).
     */
    default boolean supportsIncrementalImport(Manifest manifest) {
        return false;
    }

    /**
     * Opens an incremental import session for one image. Used only when
     * {@link #supportsIncrementalImport(Manifest)} is {@code true}; layers are fed
     * in manifest order.
     */
    default IncrementalImageImport beginIncrementalImport(ImageReference image, Manifest manifest)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "Runtime " + runtimeId() + " does not support incremental image import");
    }

    default void close() throws IOException {
        // Most adapters do not own resources.
    }
}
