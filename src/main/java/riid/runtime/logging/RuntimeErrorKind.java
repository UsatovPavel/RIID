package riid.runtime.logging;

/**
 * Runtime error categories for structured logging.
 */
public enum RuntimeErrorKind {
    OUTPUT_LIMIT,
    IO,
    INTERRUPTED,
    NON_ZERO_EXIT,
    UNKNOWN
}
