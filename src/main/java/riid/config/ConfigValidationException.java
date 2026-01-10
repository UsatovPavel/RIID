package riid.config;

/**
 * Thrown when configuration is missing required fields or has invalid values.
 */
public class ConfigValidationException extends RuntimeException {
    public ConfigValidationException(String message) {
        super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}


