package riid.core.model.manifest;

import java.util.List;

/** Manifest builders for tests. */
public final class TestManifests {

    private static final int HEX_LENGTH = 64;

    private TestManifests() {
    }

    /** A syntactically valid digest made of one repeated character. */
    public static String digest(char fill) {
        return OciLayout.DIGEST_PREFIX + String.valueOf(fill).repeat(HEX_LENGTH);
    }

    public static Descriptor config(String digest, int size) {
        return new Descriptor(MediaTypes.OCI_IMAGE_CONFIG, digest, size);
    }

    public static Descriptor gzipLayer(String digest, int size) {
        return new Descriptor(MediaTypes.OCI_IMAGE_LAYER_GZIP, digest, size);
    }

    public static Descriptor zstdLayer(String digest, int size) {
        return new Descriptor(MediaTypes.OCI_IMAGE_LAYER_ZSTD, digest, size);
    }

    public static Manifest manifest(Descriptor config, List<Descriptor> layers) {
        return new Manifest(2, MediaTypes.OCI_IMAGE_MANIFEST, config, layers);
    }
}
