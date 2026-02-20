package riid.p2p.config;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly health-check behavior.
 */
public record DragonflyHealthConfig(
        @JsonProperty("schedulerConnectTimeout") Duration schedulerConnectTimeout,
        @JsonProperty("checkInterval") Duration checkInterval,
        @JsonProperty("grpcHealthProbePath") String grpcHealthProbePath
) {
    private static final Duration DEFAULT_SCHEDULER_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(30);
    /** Minimum allowed check interval (avoid excessive load). */
    public static final Duration MIN_CHECK_INTERVAL = Duration.ofSeconds(5);
    /** Maximum allowed check interval. */
    public static final Duration MAX_CHECK_INTERVAL = Duration.ofSeconds(300);

    private static final String DEFAULT_GRPC_HEALTH_PROBE = "grpc_health_probe";

    public DragonflyHealthConfig() {
        this(DEFAULT_SCHEDULER_CONNECT_TIMEOUT, DEFAULT_CHECK_INTERVAL, null);
    }

    public DragonflyHealthConfig(Duration schedulerConnectTimeout) {
        this(schedulerConnectTimeout, DEFAULT_CHECK_INTERVAL, null);
    }

    public DragonflyHealthConfig(Duration schedulerConnectTimeout, Duration checkInterval) {
        this(schedulerConnectTimeout, checkInterval, null);
    }

    /** Path to grpc_health_probe binary. If set, used for daemon health check instead of socket file existence. */
    public String grpcHealthProbePathOrDefault() {
        return grpcHealthProbePath != null && !grpcHealthProbePath.isBlank()
                ? grpcHealthProbePath
                : null;
    }

    public Duration schedulerConnectTimeoutOrDefault() {
        return schedulerConnectTimeout != null
                ? schedulerConnectTimeout
                : DEFAULT_SCHEDULER_CONNECT_TIMEOUT;
    }

    /** Returns check interval for periodic task. PT0S or negative disables periodic check. */
    public Duration checkIntervalOrDefault() {
        if (checkInterval == null || checkInterval.isNegative()) {
            return DEFAULT_CHECK_INTERVAL;
        }
        return checkInterval.isZero() ? Duration.ZERO : checkInterval;
    }

    /** Returns true if periodic check is enabled (interval &gt; 0). */
    public boolean isPeriodicCheckEnabled() {
        return !checkIntervalOrDefault().isZero();
    }
}
