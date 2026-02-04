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
        @JsonProperty("maxRetries") Integer maxRetries
) {
    public boolean enabledOrDefault() {
        return enabled != null && enabled;
    }
}
