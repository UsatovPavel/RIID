package riid.app.daemon.metrics;

/**
 * Tar size buckets (MiB, binary) for metrics labels and dashboards,
 * plus sentinels when no bucket applies (timer {@code category} tag only).
 */
public enum ImageSizeBucket {

    MIB_0_5("0_5_mib"),
    MIB_5_10("5_10_mib"),
    MIB_10_50("10_50_mib"),
    MIB_50_100("50_100_mib"),
    MIB_100_250("100_250_mib"),
    MIB_250_500("250_500_mib"),
    MIB_500_800("500_800_mib"),
    MIB_800_2048("800_2048_mib"),
    MIB_2048_5120("2048_5120_mib"),
    GT_5120_MIB("gt_5120_mib"),

    /** Successful load, tar size not reported ({@code -1}). */
    UNKNOWN("unknown"),
    /** Failure or timeout: no tar size cohort. */
    NA("n_a");

    private final String metricLabel;

    ImageSizeBucket(String metricLabel) {
        this.metricLabel = metricLabel;
    }

    /**
     * Label value for Micrometer/Prometheus {@code category} tag (stable snake_case).
     */
    public String metricLabel() {
        return metricLabel;
    }

    /**
     * Size bucket for a tar in bytes (OCI archive). Boundaries use MiB ({@code 1024*1024}).
     * Does not return {@link #UNKNOWN} or {@link #NA}.
     */
    public static ImageSizeBucket fromTarBytes(long tarBytes) {
        long mib = tarBytes / (1024 * 1024);
        if (mib < 5) {
            return MIB_0_5;
        }
        if (mib < 10) {
            return MIB_5_10;
        }
        if (mib < 50) {
            return MIB_10_50;
        }
        if (mib < 100) {
            return MIB_50_100;
        }
        if (mib < 250) {
            return MIB_100_250;
        }
        if (mib < 500) {
            return MIB_250_500;
        }
        if (mib < 800) {
            return MIB_500_800;
        }
        if (mib < 2048) {
            return MIB_800_2048;
        }
        if (mib < 5120) {
            return MIB_2048_5120;
        }
        return GT_5120_MIB;
    }
}
