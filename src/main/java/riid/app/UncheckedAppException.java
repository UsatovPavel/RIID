package riid.app;

/**
 * Unchecked wrapper for application-level failures.
 */
public final class UncheckedAppException extends RuntimeException {
    public UncheckedAppException(String message) {
        super(message);
    }

    public UncheckedAppException(String message, Throwable cause) {
        super(message, cause);
    }
}


