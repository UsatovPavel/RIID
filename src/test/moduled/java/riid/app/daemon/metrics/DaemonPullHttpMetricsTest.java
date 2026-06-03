package riid.app.daemon.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DaemonPullHttpMetricsTest {

    @Test
    void reusesTimerPerStatusAndCodeCombination() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DaemonPullHttpMetrics metrics = new DaemonPullHttpMetrics(registry);

        long started = System.nanoTime() - 1_000_000L;
        metrics.record(started, 200, "success");
        metrics.record(started, 200, "success");
        metrics.record(started, 400, "invalid_request");

        long timerCount = registry.getMeters().stream().filter(m -> m.getId().getName().equals("riid.daemon.pull"))
                .count();
        assertEquals(2L, timerCount);
    }
}
