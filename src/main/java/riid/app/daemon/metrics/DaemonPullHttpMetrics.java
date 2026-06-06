package riid.app.daemon.metrics;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer timers for {@code POST /pull} on the daemon control plane (latency and outcome by HTTP status / JSON
 * code).
 */
public final class DaemonPullHttpMetrics {

    private static final String METRIC = "riid.daemon.pull";

    /**
     * Histogram upper bound: long {@code POST /pull} can run up to daemon request
     * timeout (~30 min).
     */
    private static final Duration PULL_HISTOGRAM_MAX = Duration.ofMinutes(30);

    private final MeterRegistry registry;
    private final ConcurrentMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();

    public DaemonPullHttpMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Records wall time for a completed {@code POST /pull} handling (including body
     * read and loader work).
     *
     * @param startNanos
     *            {@link System#nanoTime()} when handling of this request started
     *            (after method/path match)
     * @param httpStatus
     *            HTTP status sent to the client
     * @param code
     *            stable JSON {@code code} or {@code success} for 200 (low
     *            cardinality)
     */
    public void record(long startNanos, int httpStatus, String code) {
        long elapsedNanos = System.nanoTime() - startNanos;
        String statusClass = statusClass(httpStatus);
        Timer timer = timers.computeIfAbsent(new TimerKey(httpStatus, statusClass, code), this::registerTimer);
        timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private Timer registerTimer(TimerKey key) {
        return Timer.builder(METRIC).description("Daemon POST /pull handling duration")
                .tag("status", Integer.toString(key.httpStatus())).tag("status_class", key.statusClass())
                .tag("code", key.code()).publishPercentileHistogram().maximumExpectedValue(PULL_HISTOGRAM_MAX)
                .register(registry);
    }

    private static String statusClass(int status) {
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 300 && status < 400) {
            return "3xx";
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        if (status >= 500 && status < 600) {
            return "5xx";
        }
        return "other";
    }

    private record TimerKey(int httpStatus, String statusClass, String code) {
    }
}
