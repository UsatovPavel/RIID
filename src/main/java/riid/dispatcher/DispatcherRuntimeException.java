package riid.dispatcher;

/**
 * Wraps failures that happen when handing a fetched image over to a runtime adapter.
 */
public class DispatcherRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DispatcherRuntimeException(String message) {
        super(message);
    }

    public DispatcherRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}


