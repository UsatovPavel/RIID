package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Range handling policy for blob downloads.
 */
public record RangeConfig(
        @JsonProperty("mode") RangeMode mode,
        @JsonProperty("partialDigestValidation")
        @JsonAlias("partialValidation")
        PartialDigestValidation partialDigestValidation,
        @JsonProperty("fallbackOn416")
        @JsonAlias("fallbackToFullOn416")
        boolean fallbackOn416
) {
    public static final RangeMode DEFAULT_MODE = RangeMode.AUTO;
    public static final PartialDigestValidation DEFAULT_PARTIAL_DIGEST_VALIDATION = PartialDigestValidation.SKIP;
    @Deprecated
    public static final PartialDigestValidation DEFAULT_PARTIAL_VALIDATION = DEFAULT_PARTIAL_DIGEST_VALIDATION;
    public static final boolean DEFAULT_FALLBACK_ON_416 = true;

    public RangeConfig() {
        this(DEFAULT_MODE, DEFAULT_PARTIAL_DIGEST_VALIDATION, DEFAULT_FALLBACK_ON_416);
    }

    @Deprecated
    public RangeConfig(RangeMode mode, PartialValidation partialValidation, boolean fallbackToFullOn416) {
        this(
                mode,
                partialValidation != null ? PartialDigestValidation.valueOf(partialValidation.name()) : null,
                fallbackToFullOn416);
    }

    public RangeConfig {
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

    @Deprecated
    public enum PartialValidation {
        SKIP,
        REQUIRE_FULL
    }

    @Deprecated
    public PartialValidation partialValidation() {
        return PartialValidation.valueOf(partialDigestValidation.name());
    }

    @Deprecated
    public boolean fallbackToFullOn416() {
        return fallbackOn416;
    }
}

