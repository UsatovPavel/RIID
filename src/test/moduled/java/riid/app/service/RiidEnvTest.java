package riid.app.service;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class RiidEnvTest {
    private static final String ENV_CACHE_DIR = "RIID_CACHE_DIR";
    private final Map<String, String> original = new HashMap<>();

    @AfterEach
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    public void restoreEnv() {
        riid.app.service.RiidEnv.setEnvForTests(null);
        original.clear();
    }

    @Test
    void cacheDirRequiresValue() {
        setEnv(ENV_CACHE_DIR, null);
        IllegalStateException ex1 = assertThrows(IllegalStateException.class, riid.app.service.RiidEnv::cacheDir);
        assertEquals("RIID_CACHE_DIR is not set", ex1.getMessage());

        setEnv(ENV_CACHE_DIR, " ");
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, riid.app.service.RiidEnv::cacheDir);
        assertEquals("RIID_CACHE_DIR is not set", ex2.getMessage());

        setEnv(ENV_CACHE_DIR, "/tmp/cache");
        assertEquals("/tmp/cache", riid.app.service.RiidEnv.cacheDir());
    }

    private void setEnv(String key, String value) {
        if (value == null) {
            original.remove(key);
        } else {
            original.put(key, value);
        }
        riid.app.service.RiidEnv.setEnvForTests(original);
    }

    // no-op helper retained for clarity
}

