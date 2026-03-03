package riid.client.logging.service;

/**
 * Stable blob-service error codes for structured logging.
 */
public enum BlobLogErrorCode {
    BLOB_IO_ERROR,
    BLOB_SINK_ERROR,
    RANGE_DISABLED,
    RANGE_NOT_SATISFIABLE,
    RANGE_IGNORED_BY_REGISTRY,
    MISSING_CONTENT_LENGTH,
    BLOB_STREAM_ERROR,
    SINK_CLOSE_FAILED,
    SINK_STREAM_CLOSE_FAILED,
    RESPONSE_STREAM_CLOSE_FAILED,
    BLOB_DIGEST_MISMATCH,
    BLOB_SIZE_MISMATCH,
    SHA256_NOT_AVAILABLE
}
