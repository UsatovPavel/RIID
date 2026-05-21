package riid.p2p.dragonfly;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly gRPC configuration.
 * dfdaemonAddr: unix socket (e.g. unix:///var/run/dragonfly/dfdaemon.sock) or tcp (e.g. localhost:65001).
 */
public record DragonflyConfig(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("dfdaemonAddr") String dfdaemonAddr,
        @JsonProperty("schedulerAddr") String schedulerAddr,
        @JsonProperty("requestTimeout") Duration requestTimeout,
        @JsonProperty("maxRetries") Integer maxRetries
) {
    public boolean enabledOrDefault() {
        return enabled != null && enabled;
    }
}
