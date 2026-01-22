package riid.app;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class RiidEnvTest {
    private final Map<String, String> original = new HashMap<>();

    @AfterEach
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    public void restoreEnv() {
        RiidEnv.setEnvForTests(null);
        original.clear();
    }

    @Test
    void repoDefaultsWhenEnvMissing() {
        setEnv("RIID_REPO", null);
        assertEquals("library/busybox", RiidEnv.repo());
    }

    @Test
    void tagPrefersTagThenRef() {
        setEnv("RIID_TAG", "v1");
        setEnv("RIID_REF", "latest");
        assertEquals("v1", RiidEnv.tag());

        setEnv("RIID_TAG", null);
        setEnv("RIID_REF", "ref-tag");
        assertEquals("ref-tag", RiidEnv.tag());
    }

    @Test
    void digestPrefersDigestThenRefSha() {
        setEnv("RIID_DIGEST", "sha256:abc");
        setEnv("RIID_REF", "sha256:def");
        assertEquals("sha256:abc", RiidEnv.digest());

        setEnv("RIID_DIGEST", null);
        setEnv("RIID_REF", "sha256:def");
        assertEquals("sha256:def", RiidEnv.digest());

        setEnv("RIID_REF", "latest");
        assertNull(RiidEnv.digest());
    }

    @Test
    void cacheDirRequiresValue() {
        setEnv("RIID_CACHE_DIR", null);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, RiidEnv::cacheDir);
        assertEquals("RIID_CACHE_DIR is not set", ex1.getMessage());

        setEnv("RIID_CACHE_DIR", " ");
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, RiidEnv::cacheDir);
        assertEquals("RIID_CACHE_DIR is not set", ex2.getMessage());

        setEnv("RIID_CACHE_DIR", "/tmp/cache");
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

