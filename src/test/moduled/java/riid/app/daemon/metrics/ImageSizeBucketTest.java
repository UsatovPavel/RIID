package riid.app.daemon.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ImageSizeBucketTest {

    private static long mib(long n) {
        return n * 1024L * 1024L;
    }

    @Test
    void fromTarBytesMatchesExpectedBuckets() {
        assertEquals(ImageSizeBucket.MIB_0_5, ImageSizeBucket.fromTarBytes(0));
        assertEquals(ImageSizeBucket.MIB_0_5, ImageSizeBucket.fromTarBytes(mib(4)));
        assertEquals(ImageSizeBucket.MIB_5_10, ImageSizeBucket.fromTarBytes(mib(5)));
        assertEquals(ImageSizeBucket.MIB_10_50, ImageSizeBucket.fromTarBytes(mib(10)));
        assertEquals(ImageSizeBucket.MIB_50_100, ImageSizeBucket.fromTarBytes(mib(50)));
        assertEquals(ImageSizeBucket.MIB_100_250, ImageSizeBucket.fromTarBytes(mib(100)));
        assertEquals(ImageSizeBucket.MIB_250_500, ImageSizeBucket.fromTarBytes(mib(250)));
        assertEquals(ImageSizeBucket.MIB_500_800, ImageSizeBucket.fromTarBytes(mib(500)));
        assertEquals(ImageSizeBucket.MIB_800_2048, ImageSizeBucket.fromTarBytes(mib(800)));
        assertEquals(ImageSizeBucket.MIB_2048_5120, ImageSizeBucket.fromTarBytes(mib(2048)));
        assertEquals(ImageSizeBucket.GT_5120_MIB, ImageSizeBucket.fromTarBytes(mib(5120)));
    }

    @Test
    void sentinelMetricLabels() {
        assertEquals("unknown", ImageSizeBucket.UNKNOWN.metricLabel());
        assertEquals("n_a", ImageSizeBucket.NA.metricLabel());
    }

    @Test
    void sortIdxPaddedMatchesEnumOrder() {
        assertEquals("00", ImageSizeBucket.MIB_0_5.sortIdxPadded());
        assertEquals("02", ImageSizeBucket.MIB_10_50.sortIdxPadded());
        assertEquals("10", ImageSizeBucket.UNKNOWN.sortIdxPadded());
        assertEquals("11", ImageSizeBucket.NA.sortIdxPadded());
    }
}
