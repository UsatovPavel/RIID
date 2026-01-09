package riid.client.core.protocol;

/**
 * Common media type constants for Docker v2 and OCI images.
 */
public final class MediaTypes {
    private MediaTypes() {}

    // OCI
    public static final String OCI_IMAGE_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    public static final String OCI_IMAGE_INDEX = "application/vnd.oci.image.index.v1+json";
    public static final String OCI_IMAGE_CONFIG = "application/vnd.oci.image.config.v1+json";
    public static final String OCI_IMAGE_LAYER = "application/vnd.oci.image.layer.v1.tar";
    public static final String OCI_IMAGE_LAYER_GZIP = "application/vnd.oci.image.layer.v1.tar+gzip";
    public static final String OCI_IMAGE_LAYER_ZSTD = "application/vnd.oci.image.layer.v1.tar+zstd";

    // Docker schema2
    public static final String DOCKER_MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json";
    public static final String DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";
    public static final String DOCKER_IMAGE_CONFIG = "application/vnd.docker.container.image.v1+json";
    public static final String DOCKER_IMAGE_LAYER = "application/vnd.docker.image.rootfs.diff.tar";
    public static final String DOCKER_IMAGE_LAYER_GZIP = "application/vnd.docker.image.rootfs.diff.tar.gzip";
}

