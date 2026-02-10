package riid.client.api;

import java.util.Objects;

import edu.umd.cs.findbugs.annotations.NonNull;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

public record BlobRequest(
        String repository,
        String digest,
        Long expectedSizeBytes,
        String mediaType,
        @NonNull RangeSpec range
) {
    private static final String RANGE_START_MUST_BE_NON_NEGATIVE = "Range startOffsetBytes must be >= 0";

    public BlobRequest {
        range = Objects.requireNonNull(range, "range");
    }

    public String rangeHeaderValue() {
        return range.toHeaderValue();
    }

    public boolean isRangeRequest() {
        return !(range instanceof RangeSpec.All);
    }

    /**
     * Byte range request according to RFC 7233.
     * Implementations represent all supported single-range forms.
     */
    public sealed interface RangeSpec permits RangeSpec.All, RangeSpec.From, RangeSpec.Bounded, RangeSpec.Suffix {
        String toHeaderValue();

        /**
         * Full blob download, without Range header.
         */
        record All() implements RangeSpec {
            @Override
            public String toHeaderValue() {
                return null;
            }
        }

        /**
         * Range from startOffsetBytes to the end: "bytes=start-".
         */
        record From(long startOffsetBytes) implements RangeSpec {
            public From {
                if (startOffsetBytes < 0) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.RANGE, RANGE_START_MUST_BE_NON_NEGATIVE),
                            RANGE_START_MUST_BE_NON_NEGATIVE);
                }
            }

            @Override
            public String toHeaderValue() {
                return "bytes=" + startOffsetBytes + "-";
            }
        }

        /**
         * Bounded range: "bytes=start-end".
         */
        record Bounded(long startOffsetBytes, long endOffsetBytes) implements RangeSpec {
            public Bounded {
                if (startOffsetBytes < 0) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.RANGE, RANGE_START_MUST_BE_NON_NEGATIVE),
                            RANGE_START_MUST_BE_NON_NEGATIVE);
                }
                if (endOffsetBytes < 0) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.RANGE, "Range endOffsetBytes must be >= 0"),
                            "Range endOffsetBytes must be >= 0");
                }
                if (endOffsetBytes < startOffsetBytes) {
                    throw new ClientException(
                            new ClientError.Parse(
                                ClientError.ParseKind.RANGE,
                                "Range endOffsetBytes must be >= startOffsetBytes"
                            ),
                            "Range endOffsetBytes must be >= startOffsetBytes");
                }
            }

            @Override
            public String toHeaderValue() {
                return "bytes=" + startOffsetBytes + "-" + endOffsetBytes;
            }
        }

        /**
         * Suffix range: "bytes=-lastBytes".
         */
        record Suffix(long lastBytes) implements RangeSpec {
            public Suffix {
                if (lastBytes <= 0) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.RANGE, "Range lastBytes must be > 0"),
                            "Range lastBytes must be > 0");
                }
            }

            @Override
            public String toHeaderValue() {
                return "bytes=-" + lastBytes;
            }
        }
    }
}

