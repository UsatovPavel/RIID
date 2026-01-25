package riid.client.api;

import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

/**
 * Request to fetch a blob.
 */
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

    public record RangeSpec(Long start, Long end) {
        public RangeSpec {
            if (start == null && end == null) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range start/end cannot both be null"),
                        "Range start/end cannot both be null");
            }
            if (start != null && start < 0) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range start must be >= 0"),
                        "Range start must be >= 0");
            }
            if (end != null && end < 0) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range end must be >= 0"),
                        "Range end must be >= 0");
            }
            if (start != null && end != null && end < start) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Range end must be >= start"),
                        "Range end must be >= start");
            }
        }

        public String toHeaderValue() {
            String startPart = start != null ? start.toString() : "";
            String endPart = end != null ? end.toString() : "";
            return "bytes=" + startPart + "-" + endPart;
        }
    }
}

