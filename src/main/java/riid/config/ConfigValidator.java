package riid.config;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private ConfigValidator() {
    }

    public static void validate(GlobalConfig config) {
        Objects.requireNonNull(config, "config");
        ClientConfig client = config.client();
        if (client == null) {
            throw new ConfigValidationException(ConfigValidationException.Reason.MISSING_CLIENT.message());
        }
        DispatcherConfig dispatcher = config.dispatcher();
        if (dispatcher == null) {
            throw new ConfigValidationException(ConfigValidationException.Reason.MISSING_DISPATCHER.message());
        }
        validateApp(config.app());
        if (client.registriesMissing()) {
            throw new ConfigValidationException(ConfigValidationException.Reason.MISSING_REGISTRIES.message());
        }
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
            throw new ConfigValidationException(ConfigValidationException.Reason.MISSING_REGISTRIES.message());
        }
        if (registries.isEmpty()) {
            throw new ConfigValidationException(ConfigValidationException.Reason.NO_REGISTRIES.message());
        }

        registries.forEach(ep -> {
            if (ep == null) {
                throw new ConfigValidationException(ConfigValidationException.Reason.NULL_REGISTRY.message());
            }
            if (ep.scheme() == null || ep.scheme().isBlank()) {
                throw new ConfigValidationException(ConfigValidationException.Reason.MISSING_SCHEME.message());
            }
            if (ep.host() == null || ep.host().isBlank()) {
                throw new ConfigValidationException(ConfigValidationException.Reason.MISSING_HOST.message());
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
            throw new ConfigValidationException(ConfigValidationException.Reason.HTTP_MAX_RETRIES_NEGATIVE.message());
        }
        checkDuration(http.initialBackoff(), "client.http.initialBackoff");
        checkDuration(http.maxBackoff(), "client.http.maxBackoff");
        if (http.initialBackoff().compareTo(http.maxBackoff()) > 0) {
            throw new ConfigValidationException(ConfigValidationException.Reason.HTTP_BACKOFF_INVERTED.message());
        }
        String userAgent = http.userAgent();
        if (userAgent == null || userAgent.isBlank()) {
            throw new ConfigValidationException(ConfigValidationException.Reason.HTTP_USER_AGENT_BLANK.message());
        }
    }

    private static void validateAuth(AuthConfig auth) {
        if (auth == null) {
            throw new ConfigValidationException(ConfigValidationException.Reason.AUTH_MISSING.message());
        }
        if (auth.defaultTokenTtlSeconds() <= 0) {
            throw new ConfigValidationException(ConfigValidationException.Reason.AUTH_TTL_POSITIVE.message());
        }
        validatePathIfPresent(auth.certPath(), "client.auth.certPath");
        validatePathIfPresent(auth.keyPath(), "client.auth.keyPath");
        validatePathIfPresent(auth.caPath(), "client.auth.caPath");
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
            ConfigValidationException.Reason reason = switch (field) {
                case "client.http.connectTimeout" -> ConfigValidationException.Reason.HTTP_CONNECT_TIMEOUT_POSITIVE;
                case "client.http.requestTimeout" -> ConfigValidationException.Reason.HTTP_REQUEST_TIMEOUT_POSITIVE;
                case "client.http.initialBackoff" -> ConfigValidationException.Reason.HTTP_INITIAL_BACKOFF_POSITIVE;
                case "client.http.maxBackoff" -> ConfigValidationException.Reason.HTTP_MAX_BACKOFF_POSITIVE;
                default -> null;
            };
            if (reason != null) {
                throw new ConfigValidationException(reason.message());
            }
            throw new ConfigValidationException(field + " must be positive");
        }
    }

    private static void validatePathIfPresent(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        Path p = Path.of(value);
        if (!Files.exists(p)) {
            ConfigValidationException.Reason reason = switch (field) {
                case "client.auth.certPath" -> ConfigValidationException.Reason.AUTH_CERT_MISSING;
                case "client.auth.keyPath" -> ConfigValidationException.Reason.AUTH_KEY_MISSING;
                case "client.auth.caPath" -> ConfigValidationException.Reason.AUTH_CA_MISSING;
                default -> null;
            };
            if (reason != null) {
                throw new ConfigValidationException(reason.message() + ": " + value);
            }
            throw new ConfigValidationException(field + " must point to existing file: " + value);
        }
    }
}
