package riid.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Token cache backed by Caffeine with per-entry TTL.
 */
public final class TokenCache {
    private final Cache<String, Entry> cache;

    public TokenCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return ttlNanos(value);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                        return ttlNanos(value);
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    private long ttlNanos(Entry e) {
                        return TimeUnit.SECONDS.toNanos(Math.max(1, e.ttlSeconds));
                    }
                })
                .build();
    }

    public Optional<String> get(String key) {
        Entry e = cache.getIfPresent(key);
        return e != null ? Optional.of(e.token) : Optional.empty();
    }

    public void put(String key, String token, long ttlSeconds) {
        cache.put(key, new Entry(token, ttlSeconds));
    }

    private record Entry(String token, long ttlSeconds) {}
}

