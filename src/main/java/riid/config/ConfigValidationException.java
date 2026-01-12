package riid.config;

/**
 * Thrown when configuration validation fails.
 */
public class ConfigValidationException extends RuntimeException {
    public ConfigValidationException(String message) {
        super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
