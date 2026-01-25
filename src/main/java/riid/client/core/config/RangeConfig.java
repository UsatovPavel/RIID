package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Range handling policy for blob downloads.
 */
public record RangeConfig(
        @JsonProperty("mode") RangeMode mode,
        @JsonProperty("partialValidation") PartialValidation partialValidation,
        @JsonProperty("fallbackToFullOn416") boolean fallbackToFullOn416
) {
    public static final RangeMode DEFAULT_MODE = RangeMode.AUTO;
    public static final PartialValidation DEFAULT_PARTIAL_VALIDATION = PartialValidation.SKIP;
    public static final boolean DEFAULT_FALLBACK_ON_416 = true;

    public RangeConfig() {
        this(DEFAULT_MODE, DEFAULT_PARTIAL_VALIDATION, DEFAULT_FALLBACK_ON_416);
    }

    public RangeConfig {
        mode = mode != null ? mode : DEFAULT_MODE;
        partialValidation = partialValidation != null ? partialValidation : DEFAULT_PARTIAL_VALIDATION;
    }

    public enum RangeMode {
        AUTO,
        OFF
    }

    public enum PartialValidation {
        SKIP,
        REQUIRE_FULL
    }
}

