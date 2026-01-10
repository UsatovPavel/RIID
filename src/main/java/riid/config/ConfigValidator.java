package riid.config;

import riid.client.core.config.AuthConfig;
import riid.client.core.config.ClientConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.dispatcher.DispatcherConfig;

import java.util.List;
import java.util.Objects;

/**
 * Basic validation for AppConfig tree.
 */
public final class ConfigValidator {

    public void validate(AppConfig config) {
        Objects.requireNonNull(config, "config");
        require(config.client() != null, "client config is required");
        require(config.dispatcher() != null, "dispatcher config is required");

        validate(config.client());
        validate(config.dispatcher());
    }

    private void validate(ClientConfig cfg) {
        require(cfg.http() != null, "client.http is required");
        require(cfg.auth() != null, "client.auth is required");
        validate(cfg.auth());

        List<RegistryEndpoint> registries = cfg.registries();
        require(registries != null && !registries.isEmpty(), "client.registries must be non-empty");
        registries.forEach(this::validate);
    }

    private void validate(RegistryEndpoint ep) {
        require(ep != null, "registry endpoint must not be null");
        require(notBlank(ep.scheme()), "registry.scheme is required");
        require(notBlank(ep.host()), "registry.host is required");
        // port may be -1 (meaning default)
    }

    private void validate(AuthConfig auth) {
        require(auth.defaultTokenTtlSeconds() > 0, "auth.defaultTokenTtlSeconds must be > 0");
    }

    private void validate(DispatcherConfig dispatcherConfig) {
        require(dispatcherConfig.maxConcurrentRegistry() > 0, "dispatcher.maxConcurrentRegistry must be > 0");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ConfigValidationException(message);
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}


