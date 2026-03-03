package riid.core.config;

import java.util.Locale;

/**
 * Thrown when configuration is missing required fields or has invalid values.
 */
public class ConfigValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Client {
        MISSING("CLIENT: missing client configuration");

        private final String reasonMessage;

        Client(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum Dispatcher {
        MISSING("DISPATCHER: missing dispatcher configuration"),
        MAX_CONCURRENT_POSITIVE("DISPATCHER: dispatcher.maxConcurrentRegistry must be positive");

        private final String reasonMessage;

        Dispatcher(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum Registry {
        MISSING_REGISTRIES("REGISTRY: client.registries is required"),
        NO_REGISTRIES("REGISTRY: at least one registry must be configured"),
        NULL_REGISTRY("REGISTRY: registry entry must not be null"),
        MISSING_SCHEME("REGISTRY: registry.scheme is required"),
        MISSING_HOST("REGISTRY: registry.host is required");

        private final String reasonMessage;

        Registry(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum Http {
        REQUIRED("HTTP: client.http is required"),
        MAX_RETRIES_NEGATIVE("HTTP: client.http.maxRetries must be >= 0"),
        MAX_REDIRECTS_NEGATIVE("HTTP: client.http.maxRedirects must be >= 0"),
        CONNECT_TIMEOUT_POSITIVE("HTTP: client.http.connectTimeout must be positive"),
        REQUEST_TIMEOUT_POSITIVE("HTTP: client.http.requestTimeout must be positive"),
        BACKOFF_EXPONENT_BASE_MIN("HTTP: client.http.backoffExponentBase must be >= 2"),
        INITIAL_BACKOFF_POSITIVE("HTTP: client.http.initialBackoff must be positive"),
        MAX_BACKOFF_POSITIVE("HTTP: client.http.maxBackoff must be positive"),
        BACKOFF_INVERTED("HTTP: client.http.initialBackoff must not exceed maxBackoff"),
        USER_AGENT_BLANK("HTTP: client.http.userAgent must not be blank");

        private final String reasonMessage;

        Http(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum Auth {
        MISSING("AUTH: client.auth is required"),
        TTL_POSITIVE("AUTH: auth.defaultTokenTtlSeconds must be > 0"),
        CERT_MISSING("AUTH: client.auth.certPath must point to existing file"),
        KEY_MISSING("AUTH: client.auth.keyPath must point to existing file"),
        CA_MISSING("AUTH: client.auth.caPath must point to existing file"),
        CERT_KEY_PAIR_REQUIRED("AUTH: client.auth.certPath and client.auth.keyPath must be set together"),
        CERT_NOT_FILE("AUTH: client.auth.certPath must point to a regular file"),
        KEY_NOT_FILE("AUTH: client.auth.keyPath must point to a regular file"),
        CA_NOT_FILE("AUTH: client.auth.caPath must point to a regular file"),
        CERT_NOT_READABLE("AUTH: client.auth.certPath must be readable"),
        KEY_NOT_READABLE("AUTH: client.auth.keyPath must be readable"),
        CA_NOT_READABLE("AUTH: client.auth.caPath must be readable"),
        CERT_INVALID_FORMAT("AUTH: client.auth.certPath must contain valid X.509 certificate(s)"),
        KEY_INVALID_FORMAT("AUTH: client.auth.keyPath must contain a PKCS#8 private key"),
        CA_INVALID_FORMAT("AUTH: client.auth.caPath must contain valid X.509 certificate(s)");

        private final String reasonMessage;

        Auth(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum App {
        ALLOWED_REGISTRIES_BLANK("APP: app.allowedRegistries entries must not be blank"),
        TEMP_DIR_BLANK("APP: app.tempDirectory must not be blank");

        private final String reasonMessage;

        App(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum Runtime {
        MAX_STDOUT_BYTES_POSITIVE("RUNTIME: runtime.output.maxStdoutBytes must be positive"),
        MAX_STDERR_BYTES_POSITIVE("RUNTIME: runtime.output.maxStderrBytes must be positive");

        private final String reasonMessage;

        Runtime(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum P2P {
        DRAGONFLY_DFGET_PATH_REQUIRED("P2P: p2p.dragonfly.dfgetPath must not be blank when enabled"),
        DRAGONFLY_SCHEDULER_ADDR_BLANK("P2P: p2p.dragonfly.schedulerAddr must not be blank when set"),
        DRAGONFLY_MAX_RETRIES_NEGATIVE("P2P: p2p.dragonfly.maxRetries must be >= 0");

        private final String reasonMessage;

        P2P(String message) {
            this.reasonMessage = message;
        }

        public String message() {
            return reasonMessage;
        }
    }

    public enum Common {
        FIELD_POSITIVE("%s must be positive"),
        FIELD_PATH_EXISTS("%s must point to existing file: %s");

        private final String template;

        Common(String template) {
            this.template = template;
        }

        public String format(Object... args) {
            return String.format(Locale.ROOT, template, args);
        }
    }

    public ConfigValidationException(String message) {
        super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
