package riid.p2p.config;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly health-check behavior.
 */
public record DragonflyHealthConfig(
        @JsonProperty("schedulerConnectTimeout") Duration schedulerConnectTimeout
) {
    private static final Duration DEFAULT_SCHEDULER_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    public DragonflyHealthConfig() {
        this(DEFAULT_SCHEDULER_CONNECT_TIMEOUT);
    }

    public Duration schedulerConnectTimeoutOrDefault() {
        return schedulerConnectTimeout != null
                ? schedulerConnectTimeout
                : DEFAULT_SCHEDULER_CONNECT_TIMEOUT;
    }
}
