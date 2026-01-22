package riid.client.core.model.manifest;

/**
 * Supported media types for OCI/Docker manifests and layers.
 */
public enum MediaType {
    OCI_IMAGE_MANIFEST(MediaTypes.OCI_IMAGE_MANIFEST),
    OCI_IMAGE_INDEX(MediaTypes.OCI_IMAGE_INDEX),
    OCI_IMAGE_CONFIG(MediaTypes.OCI_IMAGE_CONFIG),
    OCI_IMAGE_LAYER(MediaTypes.OCI_IMAGE_LAYER),
    OCI_IMAGE_LAYER_GZIP(MediaTypes.OCI_IMAGE_LAYER_GZIP),
    OCI_IMAGE_LAYER_ZSTD(MediaTypes.OCI_IMAGE_LAYER_ZSTD),
    DOCKER_MANIFEST_V2(MediaTypes.DOCKER_MANIFEST_V2),
    DOCKER_MANIFEST_LIST(MediaTypes.DOCKER_MANIFEST_LIST),
    DOCKER_IMAGE_CONFIG(MediaTypes.DOCKER_IMAGE_CONFIG),
    DOCKER_IMAGE_LAYER(MediaTypes.DOCKER_IMAGE_LAYER),
    DOCKER_IMAGE_LAYER_GZIP(MediaTypes.DOCKER_IMAGE_LAYER_GZIP),
    GENERIC_OCTET_STREAM("application/octet-stream");

    private final String mediaType;

    MediaType(String value) {
        this.mediaType = value;
    }

    public String value() {
        return mediaType;
    }

    public static MediaType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("mediaType is blank");
        }
        for (MediaType type : values()) {
            if (type.mediaType.equals(raw)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported media type: " + raw);
    }
}

