package riid.client.api;

/**
 * Request to fetch a blob.
 */
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

public record BlobRequest(
        String repository,
        String digest,
        Long expectedSizeBytes,
        String mediaType,
        RangeSpec range
) {
    public BlobRequest(String repository, String digest, Long expectedSizeBytes, String mediaType) {
        this(repository, digest, expectedSizeBytes, mediaType, null);
    }

    public String rangeHeaderValue() {
        return range != null ? range.toHeaderValue() : null;
    }

    /**
     * Byte range request according to RFC 7233.
     * startOffsetBytes/endOffsetBytes are measured in bytes and may be null to represent open/suffix ranges.
     */
    public record RangeSpec(Long startOffsetBytes, Long endOffsetBytes) {
        public RangeSpec {
            if (startOffsetBytes == null && endOffsetBytes == null) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range startOffsetBytes/endOffsetBytes cannot both be null"),
                        "Range startOffsetBytes/endOffsetBytes cannot both be null");
            }
            if (startOffsetBytes != null && startOffsetBytes < 0) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range startOffsetBytes must be >= 0"),
                        "Range startOffsetBytes must be >= 0");
            }
            if (endOffsetBytes != null && endOffsetBytes < 0) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range endOffsetBytes must be >= 0"),
                        "Range endOffsetBytes must be >= 0");
            }
            if (startOffsetBytes != null && endOffsetBytes != null && endOffsetBytes < startOffsetBytes) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range endOffsetBytes must be >= startOffsetBytes"),
                        "Range endOffsetBytes must be >= startOffsetBytes");
            }
        }

        public String toHeaderValue() {
            String startPart = startOffsetBytes != null ? startOffsetBytes.toString() : "";
            String endPart = endOffsetBytes != null ? endOffsetBytes.toString() : "";
            return "bytes=" + startPart + "-" + endPart;
        }
    }
}

