package riid.app;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class RiidEnvTest {
    private static final String ENV_REPO = "RIID_REPO";
    private static final String ENV_TAG = "RIID_TAG";
    private static final String ENV_REF = "RIID_REF";
    private static final String ENV_DIGEST = "RIID_DIGEST";
    private static final String ENV_REGISTRY = "RIID_REGISTRY";
    private static final String ENV_CACHE_DIR = "RIID_CACHE_DIR";
    private final Map<String, String> original = new HashMap<>();

    @AfterEach
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    public void restoreEnv() {
        RiidEnv.setEnvForTests(null);
        original.clear();
    }

    @Test
    void repoDefaultsWhenEnvMissing() {
        setEnv(ENV_REPO, null);
        var id = RiidEnv.imageId();
        assertEquals("library/busybox", id.name());
    }

    @Test
    void tagPrefersTagThenRef() {
        setEnv(ENV_TAG, "v1");
        setEnv(ENV_REF, "latest");
        assertEquals("v1", RiidEnv.imageId().tag());

        setEnv(ENV_TAG, null);
        setEnv(ENV_REF, "ref-tag");
        assertEquals("ref-tag", RiidEnv.imageId().tag());
    }

    @Test
    void digestPrefersDigestThenRefSha() {
        setEnv(ENV_DIGEST, "sha256:abc");
        setEnv(ENV_REF, "sha256:def");
        assertEquals("sha256:abc", RiidEnv.imageId().digest());

        setEnv(ENV_DIGEST, null);
        setEnv(ENV_REF, "sha256:def");
        assertEquals("sha256:def", RiidEnv.imageId().digest());

        setEnv(ENV_REF, "latest");
        assertNull(RiidEnv.imageId().digest());
    }

    @Test
    void registryDefaultsWhenEnvMissing() {
        setEnv(ENV_REGISTRY, null);
        var id = RiidEnv.imageId();
        assertEquals("registry-1.docker.io", id.registry());
    }

    @Test
    void cacheDirRequiresValue() {
        setEnv(ENV_CACHE_DIR, null);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, RiidEnv::cacheDir);
        assertEquals("RIID_CACHE_DIR is not set", ex1.getMessage());

        setEnv(ENV_CACHE_DIR, " ");
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, RiidEnv::cacheDir);
        assertEquals("RIID_CACHE_DIR is not set", ex2.getMessage());

        setEnv(ENV_CACHE_DIR, "/tmp/cache");
        assertEquals("/tmp/cache", RiidEnv.cacheDir());
    }

    private void setEnv(String key, String value) {
        if (value == null) {
            original.remove(key);
        } else {
            original.put(key, value);
        }
        RiidEnv.setEnvForTests(original);
    }

    // no-op helper retained for clarity
}

