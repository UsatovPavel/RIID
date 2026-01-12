package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Auth-related configuration.
 */
public record AuthConfig(@JsonProperty("defaultTokenTtlSeconds") long defaultTokenTtlSeconds) {
    public static final long DEFAULT_TTL_SECONDS = 300L;

    public AuthConfig() {
        this(DEFAULT_TTL_SECONDS);
    }

    public AuthConfig(long defaultTokenTtlSeconds) {
        this.defaultTokenTtlSeconds = defaultTokenTtlSeconds > 0 ? defaultTokenTtlSeconds : DEFAULT_TTL;
    }
}

