package riid.app.daemon.metrics;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * End-to-end timing for {@link riid.app.cli.CliApplication.ImageLoader#load}
 * inside the daemon (registry / P2P / cache / runtime import), independent of
 * HTTP framing. Also records payload size ({@code config + layers + manifest}),
 * size-category counters, and effective throughput (payload bytes / pipeline
 * duration).
 */
public final class ImageLoadPipelineMetrics {

    /**
     * SLO cohort: throughput for pulls with tar &gt;= 10 MiB.
     */
    private static final long MIN_SLO_TAR_BYTES = 10L * 1024 * 1024;

    /**
     * Upper bound for {@link #publishPercentileHistogram()} buckets (matches
     * typical daemon {@code requestTimeout}, so p50/p95 are not stuck at the
     * default ~30s last finite {@code le}).
     */
    private static final Duration LOAD_HISTOGRAM_MAX = Duration.ofMinutes(30);
    private static final double ZERO_SECONDS = 0.0;

    private enum MetricName {
        LOAD("riid.image.load"), TAR_SIZE_CATEGORY("riid.image.load.tar.size.category"),
        /**
         * Per-bucket tar samples (PromQL mean: sum/count on this name).
         */
        TAR_SIZE_BY_CATEGORY("riid.image.load.tar.size.by.category"), TAR_SIZE_BYTES("riid.image.load.tar.size.bytes"),
        THROUGHPUT_BPS("riid.image.provide.throughput.bps"),
        THROUGHPUT_SLO_BPS("riid.image.provide.throughput.slo.bps");

        private final String metricName;

        MetricName(String value) {
            this.metricName = value;
        }

        String value() {
            return metricName;
        }
    }

    private final MeterRegistry registry;
    private final DistributionSummary tarSizeBytes;
    private final DistributionSummary throughputBps;
    private final DistributionSummary throughputSloBps;
    private final ConcurrentMap<LoadTimerKey, Timer> loadTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<ImageSizeBucket, DistributionSummary> tarSizeByCategory = new ConcurrentHashMap<>();
    private final ConcurrentMap<ImageSizeBucket, Counter> tarSizeCategoryCounters = new ConcurrentHashMap<>();

    public ImageLoadPipelineMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tarSizeBytes = DistributionSummary.builder(MetricName.TAR_SIZE_BYTES.value())
                .description("Size in bytes of the OCI tar passed to the runtime").register(registry);
        this.throughputBps = DistributionSummary.builder(MetricName.THROUGHPUT_BPS.value())
                .description("Effective throughput: tar bytes / full pipeline duration (bytes per second)")
                .publishPercentileHistogram().register(registry);
        this.throughputSloBps = DistributionSummary.builder(MetricName.THROUGHPUT_SLO_BPS.value())
                .description("Same as throughput, recorded only when tar size >= 10 MiB (SLO cohort)")
                .publishPercentileHistogram().register(registry);
    }

    public void recordSuccess(long startNanos) {
        recordSuccess(startNanos, -1L);
    }

    /**
     * @param payloadBytes
     *            payload size in bytes ({@code config + layers + manifest}), or
     *            {@code -1} to skip payload-derived metrics
     */
    public void recordSuccess(long startNanos, long payloadBytes) {
        if (payloadBytes >= 0) {
            record(startNanos, "success", ImageSizeBucket.fromTarBytes(payloadBytes));
            recordPayloadDerived(startNanos, payloadBytes);
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
        Timer timer = loadTimers.computeIfAbsent(new LoadTimerKey(result, sizeBucket), this::registerLoadTimer);
        timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private void recordPayloadDerived(long pipelineStartNanos, long payloadBytes) {
        long elapsedNanos = System.nanoTime() - pipelineStartNanos;
        double seconds = elapsedNanos / 1_000_000_000.0;
        ImageSizeBucket bucket = ImageSizeBucket.fromTarBytes(payloadBytes);

        tarSizeBytes.record(payloadBytes);

        tarSizeByCategory.computeIfAbsent(bucket, this::registerTarSizeByCategory).record(payloadBytes);

        tarSizeCategoryCounters.computeIfAbsent(bucket, this::registerTarSizeCategoryCounter).increment();

        if (seconds <= ZERO_SECONDS) {
            return;
        }
        double bps = payloadBytes / seconds;
        throughputBps.record(bps);
        if (payloadBytes >= MIN_SLO_TAR_BYTES) {
            throughputSloBps.record(bps);
        }
    }

    private Timer registerLoadTimer(LoadTimerKey key) {
        return Timer.builder(MetricName.LOAD.value()).description("End-to-end image load in daemon (loader pipeline)")
                .tag("result", key.result()).tag("category", key.bucket().metricLabel()).publishPercentileHistogram()
                .maximumExpectedValue(LOAD_HISTOGRAM_MAX).register(registry);
    }

    private DistributionSummary registerTarSizeByCategory(ImageSizeBucket bucket) {
        return DistributionSummary.builder(MetricName.TAR_SIZE_BY_CATEGORY.value())
                .description("Tar size in bytes per size bucket (for mean size vs latency dashboards)")
                .tag("category", bucket.metricLabel()).register(registry);
    }

    private Counter registerTarSizeCategoryCounter(ImageSizeBucket bucket) {
        return Counter.builder(MetricName.TAR_SIZE_CATEGORY.value())
                .description("Count of successful loads by tar size category (MiB buckets)")
                .tag("category", bucket.metricLabel()).register(registry);
    }

    private record LoadTimerKey(String result, ImageSizeBucket bucket) {
    }
}
