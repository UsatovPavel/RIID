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
        @JsonProperty("userAgent") String userAgent,
        @JsonProperty("followRedirects") boolean followRedirects,
        @JsonProperty("maxRedirects") int maxRedirects
) {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);
    private static final boolean DEFAULT_RETRY_IDEMPOTENT_ONLY = true;
    private static final String DEFAULT_USER_AGENT = "riid-registry-client";
    private static final boolean DEFAULT_FOLLOW_REDIRECTS = true;
    private static final int DEFAULT_MAX_REDIRECTS = 5;

    public HttpClientConfig() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES,
                DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_RETRY_IDEMPOTENT_ONLY, DEFAULT_USER_AGENT,
                DEFAULT_FOLLOW_REDIRECTS, DEFAULT_MAX_REDIRECTS);
    }

    @Deprecated
    public HttpClientConfig(Duration connectTimeout,
                            Duration requestTimeout,
                            int maxRetries,
                            Duration initialBackoff,
                            Duration maxBackoff,
                            boolean retryIdempotentOnly,
                            String userAgent,
                            boolean followRedirects,
                            int maxRedirects) {
        this.connectTimeout = connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff != null ? initialBackoff : DEFAULT_INITIAL_BACKOFF;
        this.maxBackoff = maxBackoff != null ? maxBackoff : DEFAULT_MAX_BACKOFF;
        this.retryIdempotentOnly = retryIdempotentOnly;
        this.userAgent = userAgent != null ? userAgent : DEFAULT_USER_AGENT;
        this.followRedirects = followRedirects;
        this.maxRedirects = maxRedirects;
        validate();
    }

    private void validate() {
        if (connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be non-negative");
        }
        if (requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be non-negative");
        }
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("backoff must be non-negative");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must be >= 0");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .connectTimeout(connectTimeout)
                .requestTimeout(requestTimeout)
                .maxRetries(maxRetries)
                .initialBackoff(initialBackoff)
                .maxBackoff(maxBackoff)
                .retryIdempotentOnly(retryIdempotentOnly)
                .userAgent(userAgent)
                .followRedirects(followRedirects)
                .maxRedirects(maxRedirects);
    }

    public static final class Builder {
        private Duration connectTimeoutValue;
        private Duration requestTimeoutValue;
        private Integer maxRetriesValue;
        private Duration initialBackoffValue;
        private Duration maxBackoffValue;
        private Boolean retryIdempotentOnlyValue;
        private String userAgentValue;
        private Boolean followRedirectsValue;
        private Integer maxRedirectsValue;

        private Builder() {
        }

        public Builder connectTimeout(Duration v) {
            this.connectTimeoutValue = v;
            return this;
        }

        public Builder requestTimeout(Duration v) {
            this.requestTimeoutValue = v;
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetriesValue = v;
            return this;
        }

        public Builder initialBackoff(Duration v) {
            this.initialBackoffValue = v;
            return this;
        }

        public Builder maxBackoff(Duration v) {
            this.maxBackoffValue = v;
            return this;
        }

        public Builder retryIdempotentOnly(boolean v) {
            this.retryIdempotentOnlyValue = v;
            return this;
        }

        public Builder userAgent(String v) {
            this.userAgentValue = v;
            return this;
        }

        public Builder followRedirects(boolean v) {
            this.followRedirectsValue = v;
            return this;
        }

        public Builder maxRedirects(int v) {
            this.maxRedirectsValue = v;
            return this;
        }

        public HttpClientConfig build() {
            return new HttpClientConfig(
                    connectTimeoutValue != null ? connectTimeoutValue : DEFAULT_CONNECT_TIMEOUT,
                    requestTimeoutValue != null ? requestTimeoutValue : DEFAULT_REQUEST_TIMEOUT,
                    maxRetriesValue != null ? maxRetriesValue : DEFAULT_MAX_RETRIES,
                    initialBackoffValue != null ? initialBackoffValue : DEFAULT_INITIAL_BACKOFF,
                    maxBackoffValue != null ? maxBackoffValue : DEFAULT_MAX_BACKOFF,
                    retryIdempotentOnlyValue != null ? retryIdempotentOnlyValue : DEFAULT_RETRY_IDEMPOTENT_ONLY,
                    userAgentValue != null ? userAgentValue : DEFAULT_USER_AGENT,
                    followRedirectsValue != null ? followRedirectsValue : DEFAULT_FOLLOW_REDIRECTS,
                    maxRedirectsValue != null ? maxRedirectsValue : DEFAULT_MAX_REDIRECTS
            );
        }
    }
}

