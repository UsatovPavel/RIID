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

    private static final String METRIC = "riid.image.load";

    /** Aligns with PR 14 SLO: throughput for pulls with tar &gt;= 10 MiB. */
    private static final long MIN_SLO_TAR_BYTES = 10L * 1024 * 1024;

    private static final String SIZE_CATEGORY_METRIC = "riid.image.load.tar.size.category";
    private static final String TAR_BYTES_METRIC = "riid.image.load.tar.size.bytes";
    private static final String THROUGHPUT_METRIC = "riid.image.load.throughput.bps";
    private static final String THROUGHPUT_SLO_METRIC = "riid.image.load.throughput.slo.bps";

    private final MeterRegistry registry;
    private final DistributionSummary tarSizeBytes;
    private final DistributionSummary throughputBps;
    private final DistributionSummary throughputSloBps;

    public ImageLoadPipelineMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tarSizeBytes = DistributionSummary.builder(TAR_BYTES_METRIC)
                .description("Size in bytes of the OCI tar passed to the runtime")
                .register(registry);
        this.throughputBps = DistributionSummary.builder(THROUGHPUT_METRIC)
                .description("Effective throughput: tar bytes / full pipeline duration (bytes per second)")
                .publishPercentileHistogram()
                .register(registry);
        this.throughputSloBps = DistributionSummary.builder(THROUGHPUT_SLO_METRIC)
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
        record(startNanos, "success");
        if (tarBytes >= 0) {
            recordTarDerived(startNanos, tarBytes);
        }
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

    private void recordTarDerived(long pipelineStartNanos, long tarBytes) {
        long elapsedNanos = System.nanoTime() - pipelineStartNanos;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double bps = seconds > 0.0 ? tarBytes / seconds : 0.0;

        tarSizeBytes.record(tarBytes);
        throughputBps.record(bps);

        Counter.builder(SIZE_CATEGORY_METRIC)
                .description("Count of successful loads by tar size category (MiB buckets)")
                .tag("category", sizeCategory(tarBytes))
                .register(registry)
                .increment();

        if (tarBytes >= MIN_SLO_TAR_BYTES) {
            throughputSloBps.record(bps);
        }
    }

    /**
     * Plan PR 14 bucket labels (tar size in bytes). Boundaries in MiB: 0-5, 5-10, 10-50, ...
     */
    static String sizeCategory(long tarBytes) {
        long mib = tarBytes / (1024 * 1024);
        if (mib < 5) {
            return "0_5_mib";
        }
        if (mib < 10) {
            return "5_10_mib";
        }
        if (mib < 50) {
            return "10_50_mib";
        }
        if (mib < 100) {
            return "50_100_mib";
        }
        if (mib < 250) {
            return "100_250_mib";
        }
        if (mib < 500) {
            return "250_500_mib";
        }
        if (mib < 800) {
            return "500_800_mib";
        }
        if (mib < 2048) {
            return "800_2048_mib";
        }
        if (mib < 5120) {
            return "2048_5120_mib";
        }
        return "gt_5120_mib";
    }
}
