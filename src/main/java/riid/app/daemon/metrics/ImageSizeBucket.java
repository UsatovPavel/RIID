package riid.app.daemon.metrics;

/**
 * Tar size buckets (MiB, binary) for metrics labels and dashboards, plus
 * sentinels when no bucket applies (timer {@code category} tag only). Dashboard
 * sort order for {@code riid:image_load:tar_category_sortidx}: keep in sync
 * with {@code config/metrics/prometheus-rules/riid-recording-rules.yaml}.
 */
public enum ImageSizeBucket {
    MIB_0_5("0-5mib", 0), MIB_5_10("5-10mib", 5), MIB_10_50("10-50mib", 10), MIB_50_100("50-100mib", 50), MIB_100_250(
            "100-250mib", 100), MIB_250_500("250-500mib", 250), MIB_500_800("500-800mib", 500), MIB_800_2048(
                    "800-2048mib", 800), MIB_2048_5120("2048-5120mib", 2048), GT_5120_MIB("gt-5120mib", 5120),

    /**
     * Successful load, tar size not reported ({@code -1}).
     */
    UNKNOWN("unknown", -1),
    /**
     * Failure or timeout: no tar size cohort.
     */
    NA("n_a", -1);

    private static final long BYTES_PER_MIB = 1024L * 1024L;

    private static final ImageSizeBucket[] SIZE_BUCKETS = {MIB_0_5, MIB_5_10, MIB_10_50, MIB_50_100, MIB_100_250,
            MIB_250_500, MIB_500_800, MIB_800_2048, MIB_2048_5120, GT_5120_MIB};

    private final String labelValue;
    private final long lowerBoundMiBValue;

    ImageSizeBucket(String metricLabel, long lowerBoundMiB) {
        this.labelValue = metricLabel;
        this.lowerBoundMiBValue = lowerBoundMiB;
    }

    /**
     * Label value for Micrometer/Prometheus {@code category} tag (stable
     * snake_case).
     */
    public String metricLabel() {
        return labelValue;
    }

    public long lowerBoundMiB() {
        return lowerBoundMiBValue;
    }

    /**
     * Size bucket for a tar in bytes (OCI archive). Boundaries use MiB
     * ({@code 1024*1024}). Does not return {@link #UNKNOWN} or {@link #NA}.
     */
    public static ImageSizeBucket fromTarBytes(long tarBytes) {
        long mib = tarBytes / BYTES_PER_MIB;
        for (int i = SIZE_BUCKETS.length - 1; i >= 0; i--) {
            ImageSizeBucket bucket = SIZE_BUCKETS[i];
            if (mib >= bucket.lowerBoundMiBValue) {
                return bucket;
            }
        }
        return MIB_0_5;
    }
}
