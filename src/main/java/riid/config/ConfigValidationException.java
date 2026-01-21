package riid.config;

/**
 * Thrown when configuration is missing required fields or has invalid values.
 */
public class ConfigValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        MISSING_CLIENT("Missing client configuration"),
        MISSING_DISPATCHER("Missing dispatcher configuration"),
        MISSING_REGISTRIES("client.registries is required"),
        NO_REGISTRIES("At least one registry must be configured"),
        NULL_REGISTRY("registry entry must not be null"),
        MISSING_SCHEME("registry.scheme is required"),
        MISSING_HOST("registry.host is required"),
        HTTP_MAX_RETRIES_NEGATIVE("client.http.maxRetries must be >= 0"),
        HTTP_CONNECT_TIMEOUT_POSITIVE("client.http.connectTimeout must be positive"),
        HTTP_REQUEST_TIMEOUT_POSITIVE("client.http.requestTimeout must be positive"),
        HTTP_INITIAL_BACKOFF_POSITIVE("client.http.initialBackoff must be positive"),
        HTTP_MAX_BACKOFF_POSITIVE("client.http.maxBackoff must be positive"),
        HTTP_BACKOFF_INVERTED("client.http.initialBackoff must not exceed maxBackoff"),
        HTTP_USER_AGENT_BLANK("client.http.userAgent must not be blank"),
        AUTH_MISSING("client.auth is required"),
        AUTH_TTL_POSITIVE("auth.defaultTokenTtlSeconds must be > 0"),
        AUTH_CERT_MISSING("client.auth.certPath must point to existing file"),
        AUTH_KEY_MISSING("client.auth.keyPath must point to existing file"),
        AUTH_CA_MISSING("client.auth.caPath must point to existing file");

        private final String reasonMessage;

        Reason(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public ConfigValidationException(String message) {
        super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
