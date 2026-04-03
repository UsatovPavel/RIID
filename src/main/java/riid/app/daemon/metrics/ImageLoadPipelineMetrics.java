package riid.app.daemon.metrics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * End-to-end timing for {@link riid.app.cli.CliApplication.ImageLoader#load} inside the daemon
 * (registry / P2P / cache / runtime import), independent of HTTP framing.
 * Also records tar size, size-category counters, and effective throughput (tar bytes / pipeline duration).
 */
public final class ImageLoadPipelineMetrics {

    /** SLO cohort: throughput for pulls with tar &gt;= 10 MiB. */
    private static final long MIN_SLO_TAR_BYTES = 10L * 1024 * 1024;

    private enum MetricName {
        LOAD("riid.image.load"),
        TAR_SIZE_CATEGORY("riid.image.load.tar.size.category"),
        /** Per-bucket tar samples (PromQL mean: sum/count on this name). */
        TAR_SIZE_BY_CATEGORY("riid.image.load.tar.size.by.category"),
        TAR_SIZE_BYTES("riid.image.load.tar.size.bytes"),
        THROUGHPUT_BPS("riid.image.load.throughput.bps"),
        THROUGHPUT_SLO_BPS("riid.image.load.throughput.slo.bps");

        private final String value;

        MetricName(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    private final MeterRegistry registry;
    private final DistributionSummary tarSizeBytes;
    private final DistributionSummary throughputBps;
    private final DistributionSummary throughputSloBps;

    public ImageLoadPipelineMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tarSizeBytes = DistributionSummary.builder(MetricName.TAR_SIZE_BYTES.value())
                .description("Size in bytes of the OCI tar passed to the runtime")
                .register(registry);
        this.throughputBps = DistributionSummary.builder(MetricName.THROUGHPUT_BPS.value())
                .description("Effective throughput: tar bytes / full pipeline duration (bytes per second)")
                .publishPercentileHistogram()
                .register(registry);
        this.throughputSloBps = DistributionSummary.builder(MetricName.THROUGHPUT_SLO_BPS.value())
                .description("Same as throughput, recorded only when tar size >= 10 MiB (SLO cohort)")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordSuccess(long startNanos) {
        recordSuccess(startNanos, -1L);
    }

    /**
     * @param tarBytes size of the OCI tar in bytes, or {@code -1} to skip tar-derived metrics
     */
    public void recordSuccess(long startNanos, long tarBytes) {
        if (tarBytes >= 0) {
            record(startNanos, "success", ImageSizeBucket.fromTarBytes(tarBytes));
            recordTarDerived(startNanos, tarBytes);
        } else {
            record(startNanos, "success", ImageSizeBucket.UNKNOWN);
        }
    }

    public void recordFailure(long startNanos) {
        record(startNanos, "error", ImageSizeBucket.NA);
    }

    public void recordTimeout(long startNanos) {
        record(startNanos, "timeout", ImageSizeBucket.NA);
    }

    private void record(long startNanos, String result, ImageSizeBucket sizeBucket) {
        long elapsedNanos = System.nanoTime() - startNanos;
        Timer.builder(MetricName.LOAD.value())
                .description("End-to-end image load in daemon (loader pipeline)")
                .tag("result", result)
                .tag("category", sizeBucket.metricLabel())
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private void recordTarDerived(long pipelineStartNanos, long tarBytes) {
        long elapsedNanos = System.nanoTime() - pipelineStartNanos;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double bps = seconds > 0.0 ? tarBytes / seconds : 0.0;

        tarSizeBytes.record(tarBytes);
        throughputBps.record(bps);

        DistributionSummary.builder(MetricName.TAR_SIZE_BY_CATEGORY.value())
                .description("Tar size in bytes per size bucket (for mean size vs latency dashboards)")
                .tag("category", ImageSizeBucket.fromTarBytes(tarBytes).metricLabel())
                .register(registry)
                .record(tarBytes);

        Counter.builder(MetricName.TAR_SIZE_CATEGORY.value())
                .description("Count of successful loads by tar size category (MiB buckets)")
                .tag("category", ImageSizeBucket.fromTarBytes(tarBytes).metricLabel())
                .register(registry)
                .increment();

        if (tarBytes >= MIN_SLO_TAR_BYTES) {
            throughputSloBps.record(bps);
        }
    }
}
