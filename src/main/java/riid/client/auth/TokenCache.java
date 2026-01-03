package riid.client.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory TTL token cache.
 */
public final class TokenCache {
    private final Clock clock;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public TokenCache() {
        this(Clock.systemUTC());
    }

    public TokenCache(Clock clock) {
        this.clock = clock;
    }

    public Optional<String> get(String key) {
        Entry e = store.get(key);
        if (e == null) return Optional.empty();
        if (Instant.now(clock).isAfter(e.expiresAt)) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.token);
    }

    public void put(String key, String token, long ttlSeconds) {
        Instant expires = Instant.now(clock).plusSeconds(Math.max(1, ttlSeconds));
        store.put(key, new Entry(token, expires));
    }

    private record Entry(String token, Instant expiresAt) {}
}

