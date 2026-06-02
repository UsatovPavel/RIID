package riid.cache.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenCacheTest {
    private static final int MAX_EVICTION_WAIT_MS = 500;
    private static final int EVICTION_POLL_STEP_MS = 10;

    @Test
    void evictsOldEntriesWhenMaximumSizeExceeded() throws Exception {
        TokenCache cache = new TokenCache(1);

        cache.put("scope-1", "token-1", 300);
        cache.put("scope-2", "token-2", 300);

        long deadline = System.nanoTime() + MAX_EVICTION_WAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline && cache.get("scope-1").isPresent()) {
            Thread.sleep(EVICTION_POLL_STEP_MS);
        }
        assertTrue(cache.get("scope-1").isEmpty());
        assertEquals("token-2", cache.get("scope-2").orElseThrow());
    }
}
