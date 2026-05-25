package riid.cache.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenCacheTest {

    @Test
    void evictsOldEntriesWhenMaximumSizeExceeded() {
        TokenCache cache = new TokenCache(1);

        cache.put("scope-1", "token-1", 300);
        cache.put("scope-2", "token-2", 300);

        assertTrue(cache.get("scope-1").isEmpty());
        assertEquals("token-2", cache.get("scope-2").orElseThrow());
    }
}
