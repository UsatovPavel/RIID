package riid.cache.oci;

import riid.core.model.manifest.MediaTypes;

/**
 * Limited media types for cached blobs.
 */
public enum CacheMediaType {
    OCTET_STREAM("application/octet-stream"), DOCKER_LAYER(MediaTypes.DOCKER_IMAGE_LAYER_GZIP),
    OCI_LAYER(MediaTypes.OCI_IMAGE_LAYER), OCI_LAYER_GZIP(MediaTypes.OCI_IMAGE_LAYER_GZIP),
    OCI_LAYER_ZSTD(MediaTypes.OCI_IMAGE_LAYER_ZSTD), CONFIG(MediaTypes.DOCKER_IMAGE_CONFIG),
    OCI_CONFIG(MediaTypes.OCI_IMAGE_CONFIG), UNKNOWN("");

    private final String rawValue;

    CacheMediaType(String rawValue) {
        this.rawValue = rawValue;
    }

    public String value() {
        return rawValue;
    }

    /**
     * Resolves media type or throws if unsupported.
     */
    public static CacheMediaType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        for (CacheMediaType t : values()) {
            if (!t.rawValue.isEmpty() && t.rawValue.equalsIgnoreCase(raw)) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
