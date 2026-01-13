package riid.cache.oci;

/**
 * Uniform validation error for cache-related value objects.
 */
public class ValidationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}


