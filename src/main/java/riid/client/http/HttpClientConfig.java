package riid.client.http;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * HTTP client configuration for registry calls.
 */
public record HttpClientConfig(
        @JsonProperty("connectTimeout") Duration connectTimeout,
        @JsonProperty("requestTimeout") Duration requestTimeout,
        @JsonProperty("maxRetries") int maxRetries,
        @JsonProperty("initialBackoff") Duration initialBackoff,
        @JsonProperty("maxBackoff") Duration maxBackoff,
        @JsonProperty("retryIdempotentOnly") boolean retryIdempotentOnly,
        @JsonProperty("userAgent") String userAgent
) {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);
    private static final boolean DEFAULT_RETRY_IDEMPOTENT_ONLY = true;
    private static final String DEFAULT_USER_AGENT = "riid-registry-client";

    public HttpClientConfig() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES,
                DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_RETRY_IDEMPOTENT_ONLY, DEFAULT_USER_AGENT);
    }

    public HttpClientConfig(Duration connectTimeout,
                            Duration requestTimeout,
                            int maxRetries,
                            Duration initialBackoff,
                            Duration maxBackoff,
                            boolean retryIdempotentOnly,
                            String userAgent) {
        this.connectTimeout = connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.initialBackoff = initialBackoff != null ? initialBackoff : DEFAULT_INITIAL_BACKOFF;
        this.maxBackoff = maxBackoff != null ? maxBackoff : DEFAULT_MAX_BACKOFF;
        this.retryIdempotentOnly = retryIdempotentOnly;
        this.userAgent = userAgent != null ? userAgent : DEFAULT_USER_AGENT;
    }
}

