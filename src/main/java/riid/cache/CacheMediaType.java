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

    private final String value;

    CacheMediaType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CacheMediaType from(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        for (CacheMediaType t : values()) {
            if (!t.value.isEmpty() && t.value.equalsIgnoreCase(raw)) {
                return t;
            }
        }
        return UNKNOWN;
    }
}

