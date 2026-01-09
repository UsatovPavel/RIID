package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Auth-related configuration.
 */
public record AuthConfig(@JsonProperty("defaultTokenTtlSeconds") long defaultTokenTtlSeconds) {
    public static final long DEFAULT_TTL = 300L;

    public AuthConfig() {
        this(DEFAULT_TTL);
    }
}

