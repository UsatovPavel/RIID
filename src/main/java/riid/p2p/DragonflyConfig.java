package riid.p2p;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly (dfget) configuration.
 */
public record DragonflyConfig(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("dfgetPath") String dfgetPath,
        @JsonProperty("schedulerAddr") String schedulerAddr,
        @JsonProperty("requestTimeout") Duration requestTimeout,
        @JsonProperty("maxRetries") Integer maxRetries,
        @JsonProperty("daemonEndpoint") String daemonEndpoint
) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final int DEFAULT_RETRIES = 0;
    private static final String DEFAULT_ENDPOINT = "/tmp/dfdaemon.sock";

    public DragonflyConfig(
            Boolean enabled,
            String dfgetPath,
            String schedulerAddr,
            Duration requestTimeout,
            Integer maxRetries
    ) {
        this(enabled, dfgetPath, schedulerAddr, requestTimeout, maxRetries, null);
    }

    public boolean enabledOrDefault() {
        return enabled != null && enabled;
    }

    public Duration requestTimeoutOrDefault() {
        return requestTimeout != null ? requestTimeout : DEFAULT_TIMEOUT;
    }

    public int maxRetriesOrDefault() {
        return maxRetries != null ? maxRetries : DEFAULT_RETRIES;
    }

    public String daemonEndpointOrDefault() {
        return daemonEndpoint != null && !daemonEndpoint.isBlank() ? daemonEndpoint : DEFAULT_ENDPOINT;
    }
}
