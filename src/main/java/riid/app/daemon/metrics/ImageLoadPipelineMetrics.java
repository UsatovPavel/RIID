package riid.app.daemon.metrics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * End-to-end timing for {@link riid.app.cli.CliApplication.ImageLoader#load} inside the daemon
 * (registry / P2P / cache / runtime import), independent of HTTP framing.
 */
public final class ImageLoadPipelineMetrics {

    private static final String METRIC = "riid.image.load";

    private final MeterRegistry registry;

    public ImageLoadPipelineMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void recordSuccess(long startNanos) {
        record(startNanos, "success");
    }

    public void recordFailure(long startNanos) {
        record(startNanos, "error");
    }

    public void recordTimeout(long startNanos) {
        record(startNanos, "timeout");
    }

    private void record(long startNanos, String result) {
        long elapsedNanos = System.nanoTime() - startNanos;
        Timer.builder(METRIC)
                .description("End-to-end image load in daemon (loader pipeline)")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
