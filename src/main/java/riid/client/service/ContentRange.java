package riid.client.service;

import riid.client.api.BlobRequest;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

/**
 * Parsed Content-Range header.
 * totalSize is nullable because registries may return wildcard form: "bytes startOffsetBytes-endOffsetBytes/*".
 */
record ContentRange(long start, long end, Long totalSize) {
    private static final String RANGE_UNIT = "bytes";
    private static final String RANGE_WILDCARD = "*";
    private static final String INVALID_CONTENT_RANGE = "Invalid Content-Range";
    private static final int EXPECTED_RANGE_PARTS = 2;

    long length() {
        return end - start + 1;
    }

    boolean coversFull() {
        return totalSize != null && start == 0 && end == totalSize - 1;
    }

    void validateAgainst(BlobRequest.RangeSpec reqRange) {
        if (reqRange == null) {
            return;
        }
        if (reqRange.startOffsetBytes() != null && !reqRange.startOffsetBytes().equals(start)) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, "Content-Range startOffsetBytes mismatch"),
                    "Content-Range startOffsetBytes mismatch");
        }
        if (reqRange.startOffsetBytes() != null && reqRange.endOffsetBytes() != null && !reqRange.endOffsetBytes().equals(end)) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, "Content-Range endOffsetBytes mismatch"),
                    "Content-Range endOffsetBytes mismatch");
        }
        if (reqRange.startOffsetBytes() == null && reqRange.endOffsetBytes() != null && totalSize != null) {
            long total = totalSize;
            long expectedStart = total - reqRange.endOffsetBytes();
            long expectedEnd = total - 1;
            if (start != expectedStart || end != expectedEnd) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Content-Range suffix mismatch"),
                        "Content-Range suffix mismatch");
            }
        }
    }

    static ContentRange parse(String header) {
        String trimmed = header.trim();
        if (!trimmed.startsWith(RANGE_UNIT)) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, "Unsupported Content-Range"),
                    "Unsupported Content-Range: " + header);
        }
        String[] parts = trimmed.split(" ", 2);
        if (parts.length != EXPECTED_RANGE_PARTS) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                    INVALID_CONTENT_RANGE + ": " + header);
        }
        String[] rangeAndTotal = parts[1].split("/", 2);
        if (rangeAndTotal.length != EXPECTED_RANGE_PARTS) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                    INVALID_CONTENT_RANGE + ": " + header);
        }
        String[] range = rangeAndTotal[0].split("-", 2);
        if (range.length != EXPECTED_RANGE_PARTS) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                    INVALID_CONTENT_RANGE + ": " + header);
        }
        long start = parseLong(range[0], "Content-Range startOffsetBytes");
        long end = parseLong(range[1], "Content-Range endOffsetBytes");
        if (end < start) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                    "Content-Range endOffsetBytes < startOffsetBytes");
        }
        Long total = null;
        String totalRaw = rangeAndTotal[1].trim();
        if (!RANGE_WILDCARD.equals(totalRaw)) {
            total = parseLong(totalRaw, "Content-Range total");
            if (total <= 0) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Invalid Content-Range total"),
                        "Content-Range total must be positive");
            }
        }
        return new ContentRange(start, end, total);
    }

    private static long parseLong(String raw, String label) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, "Invalid " + label),
                    "Invalid " + label + ": " + raw);
        }
    }
}
