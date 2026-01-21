package riid.config;

import riid.client.core.config.ClientConfig;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.DispatcherConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * Validates application configuration.
 */
public final class ConfigValidator {
    private ConfigValidator() { }

    public static void validate(AppConfig config) {
        Objects.requireNonNull(config, "config");
        ClientConfig client = config.client();
        if (client == null) {
            throw new ConfigValidationException("Missing client configuration");
        }
        DispatcherConfig dispatcher = config.dispatcher();
        if (dispatcher == null) {
            throw new ConfigValidationException("Missing dispatcher configuration");
        }
        if (client.registries().isEmpty()) {
            throw new ConfigValidationException("At least one registry must be configured");
        }
        validateHttp(client.http());
        if (dispatcher.maxConcurrentRegistry() <= 0) {
            throw new ConfigValidationException("maxConcurrentRegistry must be positive");
        }
    }

    private static void validateHttp(HttpClientConfig http) {
        Objects.requireNonNull(http, "http client configuration");
        checkDuration(http.connectTimeout(), "client.http.connectTimeout");
        checkDuration(http.requestTimeout(), "client.http.requestTimeout");
        if (http.maxRetries() < 0) {
            throw new ConfigValidationException("client.http.maxRetries must be >= 0");
        }
        checkDuration(http.initialBackoff(), "client.http.initialBackoff");
        checkDuration(http.maxBackoff(), "client.http.maxBackoff");
        if (http.initialBackoff().compareTo(http.maxBackoff()) > 0) {
            throw new ConfigValidationException("client.http.initialBackoff must not exceed maxBackoff");
        }
        String userAgent = http.userAgent();
        if (userAgent == null || userAgent.isBlank()) {
            throw new ConfigValidationException("client.http.userAgent must not be blank");
        }
    }

    private static void checkDuration(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new ConfigValidationException(field + " must be positive");
        }
    }
}
