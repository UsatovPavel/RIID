package riid.p2p.logging;

/**
 * Stable p2p download error codes for structured logging.
 */
public enum P2pLogErrorCode {
    OUTPUT_FILE_MISSING,
    DOWNLOAD_ATTEMPT_FAILED,
    DOWNLOAD_INTERRUPTED,
    CHANNEL_SHUTDOWN_INTERRUPTED
}
