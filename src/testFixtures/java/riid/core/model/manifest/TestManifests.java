package riid.core.model.manifest;

import java.util.List;

/**
 * Manifest constants and builders for tests. The media types forward to
 * production {@link MediaTypes} and the digest prefix to {@link OciLayout}, so
 * each literal is still spelled out exactly once in the repository.
 */
public final class TestManifests {

    public static final String SHA256 = OciLayout.DIGEST_PREFIX;
    public static final String CONFIG_MEDIA_TYPE = MediaTypes.OCI_IMAGE_CONFIG;
    public static final String LAYER_MEDIA_TYPE = MediaTypes.OCI_IMAGE_LAYER;
    public static final String LAYER_GZIP_MEDIA_TYPE = MediaTypes.OCI_IMAGE_LAYER_GZIP;
    public static final String LAYER_ZSTD_MEDIA_TYPE = MediaTypes.OCI_IMAGE_LAYER_ZSTD;
    public static final String MANIFEST_MEDIA_TYPE = MediaTypes.OCI_IMAGE_MANIFEST;
    private static final int HEX_LENGTH = 64;

    private TestManifests() {
    }

    /** A syntactically valid digest made of one repeated character. */
    public static String digest(char fill) {
        return SHA256 + String.valueOf(fill).repeat(HEX_LENGTH);
    }

    public static Descriptor config(String digest, int size) {
        return new Descriptor(CONFIG_MEDIA_TYPE, digest, size);
    }

    public static Descriptor gzipLayer(String digest, int size) {
        return new Descriptor(LAYER_GZIP_MEDIA_TYPE, digest, size);
    }

    public static Descriptor zstdLayer(String digest, int size) {
        return new Descriptor(LAYER_ZSTD_MEDIA_TYPE, digest, size);
    }

    public static Manifest manifest(Descriptor config, List<Descriptor> layers) {
        return new Manifest(2, MANIFEST_MEDIA_TYPE, config, layers);
    }
}
