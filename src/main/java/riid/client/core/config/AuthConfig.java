package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Auth-related configuration.
 */
public record AuthConfig(@JsonProperty("defaultTokenTtlSeconds") long defaultTokenTtlSeconds,
        @JsonProperty("certPath") String certPath, @JsonProperty("keyPath") String keyPath,
        @JsonProperty("caPath") String caPath, @JsonProperty("maxTokenCacheEntries") Integer maxTokenCacheEntries) {
    public static final long DEFAULT_TTL_SECONDS = 300L;
    public static final int DEFAULT_MAX_TOKEN_CACHE_ENTRIES = 4096;

    public AuthConfig() {
        this(DEFAULT_TTL_SECONDS, null, null, null, DEFAULT_MAX_TOKEN_CACHE_ENTRIES);
    }

    public AuthConfig(long defaultTokenTtlSeconds, String certPath, String keyPath, String caPath) {
        this(defaultTokenTtlSeconds, certPath, keyPath, caPath, null);
    }

    public AuthConfig(long defaultTokenTtlSeconds, String certPath, String keyPath, String caPath,
            Integer maxTokenCacheEntries) {
        this.defaultTokenTtlSeconds = defaultTokenTtlSeconds > 0 ? defaultTokenTtlSeconds : DEFAULT_TTL_SECONDS;
        this.certPath = certPath;
        this.keyPath = keyPath;
        this.caPath = caPath;
        this.maxTokenCacheEntries = maxTokenCacheEntries;
    }

    public int maxTokenCacheEntriesOrDefault() {
        return maxTokenCacheEntries == null || maxTokenCacheEntries <= 0
                ? DEFAULT_MAX_TOKEN_CACHE_ENTRIES
                : maxTokenCacheEntries;
    }
}
