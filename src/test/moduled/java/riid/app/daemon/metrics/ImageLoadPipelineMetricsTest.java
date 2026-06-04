package riid.app.daemon.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ImageLoadPipelineMetricsTest {

    @Test
    void cachesMetersForRepeatedBucketsAndResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ImageLoadPipelineMetrics metrics = new ImageLoadPipelineMetrics(registry);

        long started = System.nanoTime() - 1_000_000L;
        long payloadBytes = 6L * 1024L * 1024L; // 5-10 MiB bucket
        metrics.recordSuccess(started, payloadBytes);
        metrics.recordSuccess(started, payloadBytes);
        metrics.recordFailure(started);

        long loadTimers = registry.getMeters().stream().filter(m -> m.getId().getName().equals("riid.image.load"))
                .count();
        assertEquals(2L, loadTimers);

        long categorySummaries = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("riid.image.load.tar.size.by.category")).count();
        assertEquals(1L, categorySummaries);

        long categoryCounters = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("riid.image.load.tar.size.category")).count();
        assertEquals(1L, categoryCounters);
    }
}
