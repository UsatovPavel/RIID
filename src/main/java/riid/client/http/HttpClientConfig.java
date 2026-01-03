package riid.client.http;

import java.time.Duration;
import java.util.Objects;

/**
 * HTTP client configuration for registry calls.
 */
public final class HttpClientConfig {
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final boolean retryIdempotentOnly;
    private final String userAgent;

    private HttpClientConfig(Builder b) {
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.maxRetries = b.maxRetries;
        this.initialBackoff = b.initialBackoff;
        this.maxBackoff = b.maxBackoff;
        this.retryIdempotentOnly = b.retryIdempotentOnly;
        this.userAgent = b.userAgent;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public boolean retryIdempotentOnly() {
        return retryIdempotentOnly;
    }

    public String userAgent() {
        return userAgent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxRetries = 2;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(2);
        private boolean retryIdempotentOnly = true;
        private String userAgent = "riid-registry-client";

        public Builder connectTimeout(Duration v) {
            this.connectTimeout = Objects.requireNonNull(v);
            return this;
        }

        public Builder requestTimeout(Duration v) {
            this.requestTimeout = Objects.requireNonNull(v);
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = v;
            return this;
        }

        public Builder initialBackoff(Duration v) {
            this.initialBackoff = Objects.requireNonNull(v);
            return this;
        }

        public Builder maxBackoff(Duration v) {
            this.maxBackoff = Objects.requireNonNull(v);
            return this;
        }

        public Builder retryIdempotentOnly(boolean v) {
            this.retryIdempotentOnly = v;
            return this;
        }

        public Builder userAgent(String v) {
            this.userAgent = Objects.requireNonNull(v);
            return this;
        }

        public HttpClientConfig build() {
            return new HttpClientConfig(this);
        }
    }
}

