package riid.client.core.model.manifest;

/**
 * Helper with path templates for Docker Registry API v2 (pull).
 */
public final class RegistryApi {
    private RegistryApi() { }

    public static final String V2_PING = "/v2/";
    public static final String MANIFEST = "/v2/%s/manifests/%s"; // repo, ref
    public static final String BLOB = "/v2/%s/blobs/%s"; // repo, digest
    public static final String TAG_LIST = "/v2/%s/tags/list"; // repo

    public static String manifestPath(String repository, String reference) {
        return MANIFEST.formatted(repository, reference);
    }

    public static String blobPath(String repository, String digest) {
        return BLOB.formatted(repository, digest);
    }

    public static String tagListPath(String repository) {
        return TAG_LIST.formatted(repository);
    }
}

