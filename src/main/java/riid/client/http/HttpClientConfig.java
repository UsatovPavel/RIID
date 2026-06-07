package riid.client.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.core.timeout.PayloadTimeoutPolicy;

import java.time.Duration;

/**
 * HTTP client configuration for registry calls.
 */
public record HttpClientConfig(@JsonProperty("connectTimeout") Duration connectTimeout,
        @JsonProperty("requestTimeout") Duration requestTimeout,
        @JsonProperty("imageTimeoutMin") Duration imageTimeoutMin,
        @JsonProperty("imageTimeoutMax") Duration imageTimeoutMax, @JsonProperty("maxRetries") int maxRetries,
        @JsonProperty("initialBackoff") Duration initialBackoff, @JsonProperty("maxBackoff") Duration maxBackoff,
        @JsonProperty("backoffExponentBase") int backoffExponentBase,
        @JsonProperty("retryIdempotentOnly") boolean retryIdempotentOnly, @JsonProperty("userAgent") String userAgent,
        @JsonProperty("followRedirects") boolean followRedirects, @JsonProperty("maxRedirects") int maxRedirects) {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_IMAGE_TIMEOUT_MIN = Duration.ofMinutes(2);
    private static final Duration DEFAULT_IMAGE_TIMEOUT_MAX = Duration.ofMinutes(30);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);
    private static final int MIN_BACKOFF_EXPONENT_BASE = 2;
    private static final int DEFAULT_BACKOFF_EXPONENT_BASE = 2;
    private static final boolean DEFAULT_RETRY_IDEMPOTENT_ONLY = true;
    private static final String DEFAULT_USER_AGENT = "riid-registry-client";
    private static final boolean DEFAULT_FOLLOW_REDIRECTS = true;
    private static final int DEFAULT_MAX_REDIRECTS = 5;

    public HttpClientConfig() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_IMAGE_TIMEOUT_MIN, DEFAULT_IMAGE_TIMEOUT_MAX,
                DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_BACKOFF_EXPONENT_BASE,
                DEFAULT_RETRY_IDEMPOTENT_ONLY, DEFAULT_USER_AGENT, DEFAULT_FOLLOW_REDIRECTS, DEFAULT_MAX_REDIRECTS);
    }

    @Deprecated
    public HttpClientConfig(Duration connectTimeout, Duration requestTimeout, Duration imageTimeoutMin,
            Duration imageTimeoutMax, int maxRetries, Duration initialBackoff, Duration maxBackoff,
            int backoffExponentBase, boolean retryIdempotentOnly, String userAgent, boolean followRedirects,
            int maxRedirects) {
        this.connectTimeout = connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.imageTimeoutMin = imageTimeoutMin != null ? imageTimeoutMin : DEFAULT_IMAGE_TIMEOUT_MIN;
        this.imageTimeoutMax = imageTimeoutMax != null ? imageTimeoutMax : DEFAULT_IMAGE_TIMEOUT_MAX;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff != null ? initialBackoff : DEFAULT_INITIAL_BACKOFF;
        this.maxBackoff = maxBackoff != null ? maxBackoff : DEFAULT_MAX_BACKOFF;
        this.backoffExponentBase = backoffExponentBase;
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
        if (imageTimeoutMin.isNegative()) {
            throw new IllegalArgumentException("imageTimeoutMin must be non-negative");
        }
        if (imageTimeoutMax.isNegative()) {
            throw new IllegalArgumentException("imageTimeoutMax must be non-negative");
        }
        if (imageTimeoutMax.compareTo(imageTimeoutMin) < 0) {
            throw new IllegalArgumentException("imageTimeoutMax must be >= imageTimeoutMin");
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
        if (backoffExponentBase < MIN_BACKOFF_EXPONENT_BASE) {
            throw new IllegalArgumentException("backoffExponentBase must be >= 2");
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
        return new Builder().connectTimeout(connectTimeout).requestTimeout(requestTimeout)
                .imageTimeoutMin(imageTimeoutMin).imageTimeoutMax(imageTimeoutMax).maxRetries(maxRetries)
                .initialBackoff(initialBackoff).maxBackoff(maxBackoff).backoffExponentBase(backoffExponentBase)
                .retryIdempotentOnly(retryIdempotentOnly).userAgent(userAgent).followRedirects(followRedirects)
                .maxRedirects(maxRedirects);
    }

    /**
     * Interpolate timeout in [imageTimeoutMin, imageTimeoutMax] by payload size.
     * Scale anchor is 15 GiB: at/above it timeout reaches imageTimeoutMax.
     */
    public Duration timeoutForSizeBytes(long sizeBytes) {
        return PayloadTimeoutPolicy.timeoutForSizeBytes(
                sizeBytes,
                imageTimeoutMin,
                imageTimeoutMax,
                PayloadTimeoutPolicy.DEFAULT_SCALE_BYTES);
    }

    public static final class Builder {
        private Duration connectTimeoutValue;
        private Duration requestTimeoutValue;
        private Duration imageTimeoutMinValue;
        private Duration imageTimeoutMaxValue;
        private Integer maxRetriesValue;
        private Duration initialBackoffValue;
        private Duration maxBackoffValue;
        private Integer backoffExponentBaseValue;
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

        public Builder imageTimeoutMin(Duration v) {
            this.imageTimeoutMinValue = v;
            return this;
        }

        public Builder imageTimeoutMax(Duration v) {
            this.imageTimeoutMaxValue = v;
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

        public Builder backoffExponentBase(int v) {
            this.backoffExponentBaseValue = v;
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
            return new HttpClientConfig(connectTimeoutValue != null ? connectTimeoutValue : DEFAULT_CONNECT_TIMEOUT,
                    requestTimeoutValue != null ? requestTimeoutValue : DEFAULT_REQUEST_TIMEOUT,
                    imageTimeoutMinValue != null ? imageTimeoutMinValue : DEFAULT_IMAGE_TIMEOUT_MIN,
                    imageTimeoutMaxValue != null ? imageTimeoutMaxValue : DEFAULT_IMAGE_TIMEOUT_MAX,
                    maxRetriesValue != null ? maxRetriesValue : DEFAULT_MAX_RETRIES,
                    initialBackoffValue != null ? initialBackoffValue : DEFAULT_INITIAL_BACKOFF,
                    maxBackoffValue != null ? maxBackoffValue : DEFAULT_MAX_BACKOFF,
                    backoffExponentBaseValue != null ? backoffExponentBaseValue : DEFAULT_BACKOFF_EXPONENT_BASE,
                    retryIdempotentOnlyValue != null ? retryIdempotentOnlyValue : DEFAULT_RETRY_IDEMPOTENT_ONLY,
                    userAgentValue != null ? userAgentValue : DEFAULT_USER_AGENT,
                    followRedirectsValue != null ? followRedirectsValue : DEFAULT_FOLLOW_REDIRECTS,
                    maxRedirectsValue != null ? maxRedirectsValue : DEFAULT_MAX_REDIRECTS);
        }
    }
}
