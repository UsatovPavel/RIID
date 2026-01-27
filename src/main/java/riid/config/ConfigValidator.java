package riid.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import riid.app.AppConfig;
import riid.client.core.config.AuthConfig;
import riid.client.core.config.ClientConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.DispatcherConfig;
import riid.runtime.RuntimeConfig;

/**
 * Validates application configuration.
 */
public final class ConfigValidator {
    private ConfigValidator() { }

    public static void validate(GlobalConfig config) {
        Objects.requireNonNull(config, "config");
        ClientConfig client = config.client();
        if (client == null) {
            throw new ConfigValidationException("Missing client configuration");
        }
        DispatcherConfig dispatcher = config.dispatcher();
        if (dispatcher == null) {
            throw new ConfigValidationException("Missing dispatcher configuration");
        }
        validateApp(config.app());
        validateRuntime(config.runtime());
        validateRegistries(client.registries());
        validateHttp(client.http());
        validateAuth(client.auth());
        if (dispatcher.maxConcurrentRegistry() <= 0) {
            throw new ConfigValidationException("dispatcher.maxConcurrentRegistry must be positive");
        }
    }

    private static void validateRegistries(List<RegistryEndpoint> registries) {
        if (registries == null) {
            throw new ConfigValidationException("client.registries is required");
        }
        if (registries.isEmpty()) {
            throw new ConfigValidationException("At least one registry must be configured");
        }
    
        registries.forEach(ep -> {
            if (ep == null) {
                throw new ConfigValidationException("registry entry must not be null");
            }
            if (ep.scheme() == null || ep.scheme().isBlank()) {
                throw new ConfigValidationException("registry.scheme is required");
            }
            if (ep.host() == null || ep.host().isBlank()) {
                throw new ConfigValidationException("registry.host is required");
            }
        });
    }

    private static void validateHttp(HttpClientConfig http) {
        if (http == null) {
            throw new ConfigValidationException("client.http is required");
        }
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

    private static void validateAuth(AuthConfig auth) {
        if (auth == null) {
            throw new ConfigValidationException("client.auth is required");
        }
        if (auth.defaultTokenTtlSeconds() <= 0) {
            throw new ConfigValidationException("auth.defaultTokenTtlSeconds must be > 0");
        }
    }

    private static void validateApp(AppConfig app) {
        if (app == null) {
            return;
        }
        String tempDir = app.tempDirectory();
        if (tempDir != null && tempDir.isBlank()) {
            throw new ConfigValidationException("app.tempDirectory must not be blank");
        }
        for (String reg : app.allowedRegistriesOrEmpty()) {
            if (reg == null || reg.isBlank()) {
                throw new ConfigValidationException("app.allowedRegistries entries must not be blank");
            }
        }
    }

    private static void validateRuntime(RuntimeConfig runtime) {
        if (runtime == null) {
            return;
        }
        if (runtime.maxOutputBytes() != null && runtime.maxOutputBytes() <= 0) {
            throw new ConfigValidationException("runtime.maxOutputBytes must be positive");
        }
    }

    private static void checkDuration(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new ConfigValidationException(field + " must be positive");
        }
    }
}
