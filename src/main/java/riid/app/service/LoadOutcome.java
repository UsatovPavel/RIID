package riid.app.service;

import java.util.Objects;

import riid.app.core.model.ImageId;

/**
 * Result of
 * {@link ImageLoadingFacade#load(riid.app.core.model.ImageId, String)}:
 * resolved image and size in bytes of the OCI tar passed to the runtime (for
 * metrics / dashboards).
 */
public final class LoadOutcome {

    private final ImageId resolvedImageId;
    /**
     * Size of the tar archive in bytes, or {@code -1} if unknown (e.g. tests).
     */
    private final long archiveTarBytes;

    public LoadOutcome(ImageId imageId, long tarBytes) {
        this.resolvedImageId = Objects.requireNonNull(imageId, "imageId");
        this.archiveTarBytes = tarBytes;
    }

    public ImageId imageId() {
        return resolvedImageId;
    }

    public long tarBytes() {
        return archiveTarBytes;
    }

    /**
     * Same string as {@link ImageId#toString()} for API responses and CLI.
     */
    public String imageRef() {
        return resolvedImageId.toString();
    }
}
