package riid.p2p.dragonfly;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.core.timeout.PayloadTimeoutPolicy;

/**
 * Dragonfly gRPC configuration. dfdaemonAddr: unix socket (e.g.
 * unix:///var/run/dragonfly/dfdaemon.sock) or tcp (e.g. localhost:65001).
 */
public record DragonflyConfig(@JsonProperty("enabled") Boolean enabled,
        @JsonProperty("dfdaemonAddr") String dfdaemonAddr, @JsonProperty("schedulerAddr") String schedulerAddr,
        @JsonProperty("maxRetries") Integer maxRetries, @JsonProperty("imageTimeoutMin") Duration imageTimeoutMin,
        @JsonProperty("imageTimeoutMax") Duration imageTimeoutMax) {
    private static final Duration DEFAULT_IMAGE_TIMEOUT_MIN = Duration.ofMinutes(1);
    private static final Duration DEFAULT_IMAGE_TIMEOUT_MAX = Duration.ofMinutes(30);

    public boolean enabledOrDefault() {
        return enabled != null && enabled;
    }

    public Duration imageTimeoutMinOrDefault() {
        return imageTimeoutMin != null ? imageTimeoutMin : DEFAULT_IMAGE_TIMEOUT_MIN;
    }

    public Duration imageTimeoutMaxOrDefault() {
        return imageTimeoutMax != null ? imageTimeoutMax : DEFAULT_IMAGE_TIMEOUT_MAX;
    }

    public Duration requestTimeoutForSizeBytes(long sizeBytes) {
        return PayloadTimeoutPolicy.timeoutForSizeBytes(sizeBytes, imageTimeoutMinOrDefault(),
                imageTimeoutMaxOrDefault(), PayloadTimeoutPolicy.DEFAULT_SCALE_BYTES);
    }
}
