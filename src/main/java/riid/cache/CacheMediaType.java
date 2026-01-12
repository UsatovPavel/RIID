package riid.cache;

/**
 * Limited media types for cached blobs.
 */
public enum CacheMediaType {
    OCTET_STREAM("application/octet-stream"),
    DOCKER_LAYER("application/vnd.docker.image.rootfs.diff.tar.gzip"),
    OCI_LAYER("application/vnd.oci.image.layer.v1.tar"),
    CONFIG("application/vnd.docker.container.image.v1+json"),
    UNKNOWN("");

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
            throw new IllegalArgumentException("Media type is blank");
        }
        for (CacheMediaType t : values()) {
            if (!t.rawValue.isEmpty() && t.rawValue.equalsIgnoreCase(raw)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unsupported cache media type: " + raw);
    }
}

