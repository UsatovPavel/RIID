package riid.app.logging;

/**
 * Stable app-level error codes for structured logging.
 */
public enum AppLogErrorCode {
    REQUEST_FAILED,
    USAGE_ERROR,
    RUNTIME_NOT_FOUND,
    CONFIG_RESOLVE_FAILED,
    MANIFEST_FETCH_FAILED,
    ENGINE_IMPORT_FAILED,
    ARCHIVE_BUILD_FAILED
}
