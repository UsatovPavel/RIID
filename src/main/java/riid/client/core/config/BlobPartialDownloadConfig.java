package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Range handling policy for blob downloads.
 */
public record BlobPartialDownloadConfig(
        @JsonProperty("mode") RangeMode mode,
        @JsonProperty("partialDigestValidation") PartialDigestValidation partialDigestValidation,
        @JsonProperty("retryWithoutRangeOnUnsatisfiableRange") boolean retryWithoutRangeOnUnsatisfiableRange
) {
    public static final RangeMode DEFAULT_MODE = RangeMode.AUTO;
    public static final PartialDigestValidation DEFAULT_PARTIAL_DIGEST_VALIDATION = PartialDigestValidation.SKIP;
    public static final boolean DEFAULT_RETRY_WITHOUT_RANGE_ON_UNSATISFIABLE_RANGE = true;

    public BlobPartialDownloadConfig() {
        this(DEFAULT_MODE, DEFAULT_PARTIAL_DIGEST_VALIDATION, DEFAULT_RETRY_WITHOUT_RANGE_ON_UNSATISFIABLE_RANGE);
    }

    public BlobPartialDownloadConfig {
        mode = mode != null ? mode : DEFAULT_MODE;
        partialDigestValidation = partialDigestValidation != null
                ? partialDigestValidation
                : DEFAULT_PARTIAL_DIGEST_VALIDATION;
    }

    public enum RangeMode {
        AUTO,
        OFF
    }

    public enum PartialDigestValidation {
        SKIP,
        REQUIRE_FULL
    }
}

