package riid.core.model.manifest;

/**
 * Names fixed by the OCI image layout and image spec: the files a layout is
 * made of and the JSON fields RIID reads or writes in them. Shared by the app
 * layer that builds layouts and the runtime adapters that hand them over.
 */
public final class OciLayout {
    private OciLayout() {
    }

    // Layout files and directories
    public static final String MARKER_FILE = "oci-layout";
    public static final String INDEX_JSON = "index.json";
    public static final String BLOBS_DIR = "blobs";
    public static final String SHA256_DIR = "sha256";
    public static final String DIGEST_PREFIX = "sha256:";
    public static final String MARKER_CONTENT = "{\"imageLayoutVersion\":\"1.0.0\"}";

    // Descriptor and index fields
    public static final String MANIFESTS = "manifests";
    public static final String MEDIA_TYPE = "mediaType";
    public static final String DIGEST = "digest";
    public static final String SIZE = "size";
    public static final String ANNOTATIONS = "annotations";
    public static final String REF_NAME_ANNOTATION = "org.opencontainers.image.ref.name";

    // Manifest and config fields
    public static final String CONFIG = "config";
    public static final String LAYERS = "layers";
    public static final String ROOTFS = "rootfs";
    public static final String DIFF_IDS = "diff_ids";
    public static final String HISTORY = "history";
}
