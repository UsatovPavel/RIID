package riid.app;

/**
 * Unchecked wrapper for application-level failures.
 */
public final class UncheckedAppException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UncheckedAppException(String message) {
        super(message);
    }

    public UncheckedAppException(String message, Throwable cause) {
        super(message, cause);
    }
}


