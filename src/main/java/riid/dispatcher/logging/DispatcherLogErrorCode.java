package riid.dispatcher.logging;

/**
 * Stable dispatcher error codes for structured logging.
 */
public enum DispatcherLogErrorCode {
    P2P_MISS,
    P2P_FETCH_FAILED,
    REGISTRY_FETCH_FAILED,
    CACHE_PUT_VALIDATION_ERROR,
    CACHE_PUT_UNSUPPORTED_MEDIA_TYPE,
    CACHE_PUT_FAILED,
    P2P_PUBLISH_FAILED,
    TEMP_FILE_DELETE_FAILED
}
