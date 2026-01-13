package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Auth-related configuration.
 */
public record AuthConfig(
        @JsonProperty("defaultTokenTtlSeconds") long defaultTokenTtlSeconds,
        @JsonProperty("certPath") String certPath,
        @JsonProperty("keyPath") String keyPath,
        @JsonProperty("caPath") String caPath
) {
    public static final long DEFAULT_TTL_SECONDS = 300L;

    public AuthConfig() {
        this(DEFAULT_TTL_SECONDS, null, null, null);
    }

    public AuthConfig(long defaultTokenTtlSeconds, String certPath, String keyPath, String caPath) {
        this.defaultTokenTtlSeconds = defaultTokenTtlSeconds > 0 ? defaultTokenTtlSeconds : DEFAULT_TTL_SECONDS;
        this.certPath = certPath;
        this.keyPath = keyPath;
        this.caPath = caPath;
    }
}

