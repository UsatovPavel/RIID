package riid.client.logging.service;

import org.slf4j.Logger;

import riid.core.logging.StructuredLog;

/**
 * Structured event helpers for blob service.
 */
public final class BlobStructuredEvents {
    private static final String COMPONENT = "client";
    private static final String OPERATION = "blob.fetch";

    private BlobStructuredEvents() {
    }

    public static void blobIoError(Logger logger, String repository, String digest, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.fetch",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                BlobLogErrorCode.BLOB_IO_ERROR.name(),
                errorKind(error),
                StructuredLog.fields(
                        "repository", repository,
                        "digest", digest,
                        "error_message", errorMessage(error)
                )
        );
    }

    public static void blobSinkError(Logger logger, String repository, String digest, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.fetch",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                BlobLogErrorCode.BLOB_SINK_ERROR.name(),
                errorKind(error),
                StructuredLog.fields(
                        "repository", repository,
                        "digest", digest,
                        "error_message", errorMessage(error)
                )
        );
    }

    public static void rangeDisabled(Logger logger, String digest) {
        StructuredLog.warn(
                logger,
                "blob.range",
                COMPONENT,
                OPERATION,
                "disabled",
                0L,
                BlobLogErrorCode.RANGE_DISABLED.name(),
                "config",
                StructuredLog.fields("digest", digest)
        );
    }

    public static void rangeNotSatisfiableRetry(Logger logger, String digest, String rangeValue) {
        StructuredLog.warn(
                logger,
                "blob.range",
                COMPONENT,
                OPERATION,
                "retry_without_range",
                0L,
                BlobLogErrorCode.RANGE_NOT_SATISFIABLE.name(),
                "range",
                StructuredLog.fields(
                        "digest", digest,
                        "range", rangeValue
                )
        );
    }

    public static void rangeIgnoredByRegistry(Logger logger, String digest, String rangeValue) {
        StructuredLog.warn(
                logger,
                "blob.range",
                COMPONENT,
                OPERATION,
                "ignored_by_registry",
                0L,
                BlobLogErrorCode.RANGE_IGNORED_BY_REGISTRY.name(),
                "protocol",
                StructuredLog.fields(
                        "digest", digest,
                        "range", rangeValue
                )
        );
    }

    public static void missingContentLength(Logger logger, String digest) {
        StructuredLog.warn(
                logger,
                "blob.fetch",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                BlobLogErrorCode.MISSING_CONTENT_LENGTH.name(),
                "protocol",
                StructuredLog.fields("digest", digest)
        );
    }

    public static void blobStreamError(Logger logger, String repository, String digest, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.fetch",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                BlobLogErrorCode.BLOB_STREAM_ERROR.name(),
                errorKind(error),
                StructuredLog.fields(
                        "repository", repository,
                        "digest", digest,
                        "error_message", errorMessage(error)
                )
        );
    }

    public static void sinkCloseFailed(Logger logger, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.resource.close",
                COMPONENT,
                OPERATION,
                "failed",
                0L,
                BlobLogErrorCode.SINK_CLOSE_FAILED.name(),
                errorKind(error),
                StructuredLog.fields("error_message", errorMessage(error))
        );
    }

    public static void sinkStreamCloseFailed(Logger logger, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.resource.close",
                COMPONENT,
                OPERATION,
                "failed",
                0L,
                BlobLogErrorCode.SINK_STREAM_CLOSE_FAILED.name(),
                errorKind(error),
                StructuredLog.fields("error_message", errorMessage(error))
        );
    }

    public static void responseStreamCloseFailed(Logger logger, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.resource.close",
                COMPONENT,
                OPERATION,
                "failed",
                0L,
                BlobLogErrorCode.RESPONSE_STREAM_CLOSE_FAILED.name(),
                errorKind(error),
                StructuredLog.fields("error_message", errorMessage(error))
        );
    }

    public static void digestMismatch(Logger logger, String expected, String computed) {
        StructuredLog.warn(
                logger,
                "blob.verify",
                COMPONENT,
                OPERATION,
                "failed",
                0L,
                BlobLogErrorCode.BLOB_DIGEST_MISMATCH.name(),
                "integrity",
                StructuredLog.fields(
                        "expected_digest", expected,
                        "computed_digest", computed
                )
        );
    }

    public static void sizeMismatch(Logger logger, long expected, long actual) {
        StructuredLog.warn(
                logger,
                "blob.verify",
                COMPONENT,
                OPERATION,
                "failed",
                0L,
                BlobLogErrorCode.BLOB_SIZE_MISMATCH.name(),
                "integrity",
                StructuredLog.fields(
                        "expected_size", expected,
                        "actual_size", actual
                )
        );
    }

    public static void sha256NotAvailable(Logger logger, String input, String output, Throwable error) {
        StructuredLog.warn(
                logger,
                "blob.hash",
                COMPONENT,
                OPERATION,
                "failed",
                0L,
                BlobLogErrorCode.SHA256_NOT_AVAILABLE.name(),
                errorKind(error),
                StructuredLog.fields(
                        "input", input,
                        "output", output,
                        "error_message", errorMessage(error)
                )
        );
    }

    private static String errorKind(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private static String errorMessage(Throwable error) {
        return error == null ? "none" : String.valueOf(error.getMessage());
    }
}
