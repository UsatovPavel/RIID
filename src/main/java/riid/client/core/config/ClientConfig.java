package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.client.http.HttpClientConfig;

import java.util.List;
import java.util.List;

/**
 * Aggregated client module configuration.
 */
public record ClientConfig(
        @JsonProperty("http") HttpClientConfig http,
        @JsonProperty("auth") AuthConfig auth,
        @JsonProperty("registries") List<RegistryEndpoint> registries
) {
    public ClientConfig {
        registries = registries == null ? null : List.copyOf(registries);
    }

    @Override
    public List<RegistryEndpoint> registries() {
        return registries == null ? null : List.copyOf(registries);
    }
}
