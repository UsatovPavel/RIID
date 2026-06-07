package riid.core.timeout;

import java.time.Duration;

/**
 * Resolves timeout by payload size using linear interpolation in [minTimeout,
 * maxTimeout] range.
 */
public final class PayloadTimeoutPolicy {
    public static final long DEFAULT_SCALE_BYTES = 15L * 1024L * 1024L * 1024L; // 15 GiB

    private PayloadTimeoutPolicy() {
    }

    public static Duration timeoutForSizeBytes(long sizeBytes, Duration minTimeout, Duration maxTimeout,
            long scaleBytes) {
        if (minTimeout == null || maxTimeout == null) {
            throw new IllegalArgumentException("minTimeout and maxTimeout must be non-null");
        }
        if (minTimeout.isNegative() || minTimeout.isZero()) {
            throw new IllegalArgumentException("minTimeout must be positive");
        }
        if (maxTimeout.isNegative() || maxTimeout.isZero()) {
            throw new IllegalArgumentException("maxTimeout must be positive");
        }
        if (maxTimeout.compareTo(minTimeout) < 0) {
            throw new IllegalArgumentException("maxTimeout must be >= minTimeout");
        }
        if (scaleBytes <= 0) {
            throw new IllegalArgumentException("scaleBytes must be positive");
        }
        if (sizeBytes <= 0) {
            return minTimeout;
        }
        long minMs = minTimeout.toMillis();
        long maxMs = maxTimeout.toMillis();
        if (maxMs <= minMs) {
            return minTimeout;
        }
        double ratio = Math.min(1.0, (double) sizeBytes / scaleBytes);
        long timeoutMs = minMs + Math.round((maxMs - minMs) * ratio);
        return Duration.ofMillis(Math.min(timeoutMs, maxMs));
    }
}
