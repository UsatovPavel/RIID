package riid.app.service;

import java.util.Objects;

import riid.app.core.model.ImageId;

/**
 * Result of
 * {@link ImageLoadingFacade#load(riid.app.core.model.ImageId, String)}:
 * resolved image and logical payload size in bytes
 * ({@code config + layers + manifest}) for metrics / dashboards.
 */
public final class LoadOutcome {

    private final ImageId resolvedImageId;
    private final long payloadBytes;

    public LoadOutcome(ImageId imageId, long payloadBytes) {
        this.resolvedImageId = Objects.requireNonNull(imageId, "imageId");
        this.payloadBytes = payloadBytes;
    }

    public ImageId imageId() {
        return resolvedImageId;
    }

    public long payloadBytes() {
        return payloadBytes;
    }

    /**
     * Backward-compatible alias for historical API name.
     */
    public long tarBytes() {
        return payloadBytes;
    }

    /**
     * Same string as {@link ImageId#toString()} for API responses and CLI.
     */
    public String imageRef() {
        return resolvedImageId.toString();
    }
}
