package riid.p2p.logging;

import java.nio.file.Path;

import org.slf4j.Logger;

import riid.core.logging.StructuredLog;

/**
 * P2P download lifecycle structured events.
 */
public final class P2pStructuredEvents {
    private static final String COMPONENT = "p2p";
    private static final String OPERATION = "downloadTask";

    private P2pStructuredEvents() {
    }

    public static void downloadStart(Logger logger,
                                     String url,
                                     Path outputPath,
                                     long timeoutMs,
                                     int maxAttempts) {
        StructuredLog.info(
                logger,
                "p2p.download",
                COMPONENT,
                OPERATION,
                "start",
                0L,
                null,
                null,
                StructuredLog.fields(
                        "url", url,
                        "output_path", String.valueOf(outputPath),
                        "timeout_ms", timeoutMs,
                        "max_attempts", maxAttempts
                )
        );
    }

    public static void downloadRetry(Logger logger, int attempt, int maxAttempts) {
        StructuredLog.info(
                logger,
                "p2p.download",
                COMPONENT,
                OPERATION,
                "retry",
                0L,
                null,
                null,
                StructuredLog.fields(
                        "attempt", attempt,
                        "max_attempts", maxAttempts
                )
        );
    }

    public static void outputMissing(Logger logger, Path outputPath, int attempt) {
        StructuredLog.warn(
                logger,
                "p2p.download",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                P2pLogErrorCode.OUTPUT_FILE_MISSING.name(),
                "io",
                StructuredLog.fields(
                        "attempt", attempt,
                        "output_path", String.valueOf(outputPath)
                )
        );
    }

    public static void downloadSuccess(Logger logger, Path outputPath, long durationMs, int attempt) {
        StructuredLog.info(
                logger,
                "p2p.download",
                COMPONENT,
                OPERATION,
                "success",
                durationMs,
                null,
                null,
                StructuredLog.fields(
                        "attempt", attempt,
                        "output_path", String.valueOf(outputPath)
                )
        );
    }

    public static void downloadAttemptFailed(Logger logger,
                                             long durationMs,
                                             int attempt,
                                             int maxAttempts,
                                             String grpcStatusCode,
                                             boolean retryable,
                                             boolean willRetry) {
        StructuredLog.warn(
                logger,
                "p2p.download",
                COMPONENT,
                OPERATION,
                "error",
                durationMs,
                P2pLogErrorCode.DOWNLOAD_ATTEMPT_FAILED.name(),
                "grpc",
                StructuredLog.fields(
                        "attempt", attempt,
                        "max_attempts", maxAttempts,
                        "grpc_status", grpcStatusCode,
                        "retryable", retryable,
                        "will_retry", willRetry
                )
        );
    }

    public static void downloadInterrupted(Logger logger, int attempt) {
        StructuredLog.error(
                logger,
                "p2p.download",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                P2pLogErrorCode.DOWNLOAD_INTERRUPTED.name(),
                "interrupted",
                StructuredLog.fields("attempt", attempt)
        );
    }

    public static void shutdownInterrupted(Logger logger) {
        StructuredLog.warn(
                logger,
                "p2p.channel.shutdown",
                COMPONENT,
                "channel.close",
                "interrupted",
                0L,
                P2pLogErrorCode.CHANNEL_SHUTDOWN_INTERRUPTED.name(),
                "interrupted",
                null
        );
    }
}
