package riid.core.config;

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
import riid.p2p.DragonflyConfig;
import riid.p2p.P2PConfig;
import riid.runtime.OutputConfig;
import riid.runtime.RuntimeConfig;

/**
 * Validates application configuration.
 */
public final class ConfigValidator {
    private static final int MIN_BACKOFF_EXPONENT_BASE = 2;

    private ConfigValidator() {
    }

    public static void validate(GlobalConfig config) {
        Objects.requireNonNull(config, "config");
        ClientConfig client = config.client();
        if (client == null) {
            throw new ConfigValidationException(ConfigValidationException.Client.MISSING.message());
        }
        DispatcherConfig dispatcher = config.dispatcher();
        if (dispatcher == null) {
            throw new ConfigValidationException(ConfigValidationException.Dispatcher.MISSING.message());
        }
        validateApp(config.app());
        if (client.registriesMissing()) {
            throw new ConfigValidationException(ConfigValidationException.Registry.MISSING_REGISTRIES.message());
        }
        validateRuntime(config.runtime());
        validateP2P(config.p2p());
        validateRegistries(client.registries());
        validateHttp(client.http());
        validateAuth(client.auth());
        if (dispatcher.maxConcurrentRegistry() <= 0) {
            throw new ConfigValidationException(ConfigValidationException.Dispatcher.MAX_CONCURRENT_POSITIVE.message());
        }
    }

    private static void validateRegistries(List<RegistryEndpoint> registries) {
        if (registries == null) {
            throw new ConfigValidationException(ConfigValidationException.Registry.MISSING_REGISTRIES.message());
        }
        if (registries.isEmpty()) {
            throw new ConfigValidationException(ConfigValidationException.Registry.NO_REGISTRIES.message());
        }

        registries.forEach(ep -> {
            if (ep == null) {
                throw new ConfigValidationException(ConfigValidationException.Registry.NULL_REGISTRY.message());
            }
            if (ep.scheme() == null || ep.scheme().isBlank()) {
                throw new ConfigValidationException(ConfigValidationException.Registry.MISSING_SCHEME.message());
            }
            if (ep.host() == null || ep.host().isBlank()) {
                throw new ConfigValidationException(ConfigValidationException.Registry.MISSING_HOST.message());
            }
        });
    }

    private static void validateHttp(HttpClientConfig http) {
        if (http == null) {
            throw new ConfigValidationException(ConfigValidationException.Http.REQUIRED.message());
        }
        checkDuration(http.connectTimeout(), "client.http.connectTimeout");
        checkDuration(http.requestTimeout(), "client.http.requestTimeout");
        if (http.maxRetries() < 0) {
            throw new ConfigValidationException(ConfigValidationException.Http.MAX_RETRIES_NEGATIVE.message());
        }
        if (http.maxRedirects() < 0) {
            throw new ConfigValidationException(ConfigValidationException.Http.MAX_REDIRECTS_NEGATIVE.message());
        }
        checkDuration(http.initialBackoff(), "client.http.initialBackoff");
        checkDuration(http.maxBackoff(), "client.http.maxBackoff");
        if (http.initialBackoff().compareTo(http.maxBackoff()) > 0) {
            throw new ConfigValidationException(ConfigValidationException.Http.BACKOFF_INVERTED.message());
        }
        if (http.backoffExponentBase() < MIN_BACKOFF_EXPONENT_BASE) {
            throw new ConfigValidationException(
                ConfigValidationException.Http.BACKOFF_EXPONENT_BASE_MIN.message()
            );
        }
        String userAgent = http.userAgent();
        if (userAgent == null || userAgent.isBlank()) {
            throw new ConfigValidationException(ConfigValidationException.Http.USER_AGENT_BLANK.message());
        }
    }

    private static void validateAuth(AuthConfig auth) {
        if (auth == null) {
            throw new ConfigValidationException(ConfigValidationException.Auth.MISSING.message());
        }
        if (auth.defaultTokenTtlSeconds() <= 0) {
            throw new ConfigValidationException(ConfigValidationException.Auth.TTL_POSITIVE.message());
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
            throw new ConfigValidationException(ConfigValidationException.App.TEMP_DIR_BLANK.message());
        }
        for (String reg : app.allowedRegistriesOrEmpty()) {
            if (reg == null || reg.isBlank()) {
                throw new ConfigValidationException(ConfigValidationException.App.ALLOWED_REGISTRIES_BLANK.message());
            }
        }
    }

    private static void validateRuntime(RuntimeConfig runtime) {
        if (runtime == null) {
            return;
        }
        String dockerCmd = runtime.dockerCmd();
        if (dockerCmd != null && dockerCmd.isBlank()) {
            throw new ConfigValidationException("runtime.dockerCmd must not be blank");
        }
        OutputConfig output = runtime.output();
        if (output == null) {
            return;
        }
        if (output.captureStdout() && (output.maxStdoutBytes() == null || output.maxStdoutBytes() <= 0)) {
            throw new ConfigValidationException(ConfigValidationException.Runtime.MAX_STDOUT_BYTES_POSITIVE.message());
        }
        if (output.captureStderr() && (output.maxStderrBytes() == null || output.maxStderrBytes() <= 0)) {
            throw new ConfigValidationException(ConfigValidationException.Runtime.MAX_STDERR_BYTES_POSITIVE.message());
        }
    }

    private static void validateP2P(P2PConfig p2p) {
        if (p2p == null) {
            return;
        }
        DragonflyConfig dragonfly = p2p.dragonfly();
        if (dragonfly == null) {
            return;
        }
        if (dragonfly.enabledOrDefault()) {
            String dfgetPath = dragonfly.dfgetPath();
            if (dfgetPath == null || dfgetPath.isBlank()) {
                throw new ConfigValidationException(
                        ConfigValidationException.P2P.DRAGONFLY_DFGET_PATH_REQUIRED.message());
            }
        }
        String schedulerAddr = dragonfly.schedulerAddr();
        if (schedulerAddr != null && schedulerAddr.isBlank()) {
            throw new ConfigValidationException(ConfigValidationException.P2P.DRAGONFLY_SCHEDULER_ADDR_BLANK.message());
        }
        if (dragonfly.maxRetries() != null && dragonfly.maxRetries() < 0) {
            throw new ConfigValidationException(ConfigValidationException.P2P.DRAGONFLY_MAX_RETRIES_NEGATIVE.message());
        }
        if (dragonfly.requestTimeout() != null) {
            checkDuration(dragonfly.requestTimeout(), "p2p.dragonfly.requestTimeout");
        }
    }

    private static void checkDuration(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            String message = switch (field) {
                case "client.http.connectTimeout" -> ConfigValidationException.Http.CONNECT_TIMEOUT_POSITIVE.message();
                case "client.http.requestTimeout" -> ConfigValidationException.Http.REQUEST_TIMEOUT_POSITIVE.message();
                case "client.http.initialBackoff" -> ConfigValidationException.Http.INITIAL_BACKOFF_POSITIVE.message();
                case "client.http.maxBackoff" -> ConfigValidationException.Http.MAX_BACKOFF_POSITIVE.message();
                default -> null;
            };
            if (message != null) {
                throw new ConfigValidationException(message);
            }
            throw new ConfigValidationException(ConfigValidationException.Common.FIELD_POSITIVE.format(field));
        }
    }

    private static void validatePathIfPresent(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        Path p = Path.of(value);
        if (!Files.exists(p)) {
            String message = switch (field) {
                case "client.auth.certPath" -> ConfigValidationException.Auth.CERT_MISSING.message();
                case "client.auth.keyPath" -> ConfigValidationException.Auth.KEY_MISSING.message();
                case "client.auth.caPath" -> ConfigValidationException.Auth.CA_MISSING.message();
                default -> null;
            };
            if (message != null) {
                throw new ConfigValidationException(message + ": " + value);
            }
            throw new ConfigValidationException(ConfigValidationException.Common.FIELD_PATH_EXISTS.format(field, value));
        }
    }
}
