package riid.cache;

import org.junit.jupiter.api.Test;
import riid.cache.oci.CacheMediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheMediaTypeTest {

    @Test
    void resolvesKnownTypes() {
        assertEquals(CacheMediaType.OCI_LAYER, CacheMediaType.from("application/vnd.oci.image.layer.v1.tar"));
        assertEquals(CacheMediaType.OCI_LAYER_GZIP, CacheMediaType.from("application/vnd.oci.image.layer.v1.tar+gzip"));
        assertEquals(CacheMediaType.OCI_LAYER_ZSTD, CacheMediaType.from("application/vnd.oci.image.layer.v1.tar+zstd"));
        assertEquals(CacheMediaType.CONFIG, CacheMediaType.from("application/vnd.docker.container.image.v1+json"));
        assertEquals(CacheMediaType.OCI_CONFIG, CacheMediaType.from("application/vnd.oci.image.config.v1+json"));
    }

    @Test
    void unknownOrBlankBecomesUnknown() {
        assertEquals(CacheMediaType.UNKNOWN, CacheMediaType.from(null));
        assertEquals(CacheMediaType.UNKNOWN, CacheMediaType.from(""));
        assertEquals(CacheMediaType.UNKNOWN, CacheMediaType.from("text/plain"));
    }
}
