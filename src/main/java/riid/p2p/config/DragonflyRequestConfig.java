package riid.p2p.config;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly request-level CLI options.
 */
public record DragonflyRequestConfig(
        @JsonProperty("requestTimeout") Duration requestTimeout,
        @JsonProperty("maxRetries") Integer maxRetries,
        @JsonProperty("application") String application,
        @JsonProperty("tag") String tag,
        @JsonProperty("headers") List<String> headers
) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final int DEFAULT_RETRIES = 0;

    public DragonflyRequestConfig() {
        this(DEFAULT_TIMEOUT, DEFAULT_RETRIES, "", "", List.of());
    }

    public Duration requestTimeoutOrDefault() {
        return requestTimeout != null ? requestTimeout : DEFAULT_TIMEOUT;
    }

    public int maxRetriesOrDefault() {
        Integer retries = maxRetries;
        return retries == null ? DEFAULT_RETRIES : retries;
    }

    public List<String> headersOrEmpty() {
        return headers != null ? headers : List.of();
    }
}
