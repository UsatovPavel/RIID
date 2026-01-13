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
        if (registries != null) {
            registries = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(registries));
        }
    }

    @Override
    public List<RegistryEndpoint> registries() {
        if (registries == null) {
            return null;
        }
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(registries));
    }
}
