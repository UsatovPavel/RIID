package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.client.http.HttpClientConfig;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Aggregated client module configuration.
 */
public record ClientConfig(
        @JsonProperty("http") HttpClientConfig http,
        @JsonProperty("auth") AuthConfig auth,
        @JsonProperty("registries") List<RegistryEndpoint> registries
) {
    public ClientConfig {
        if (registries == null) {
            registries = List.of();
        } else {
            registries = Collections.unmodifiableList(new ArrayList<>(registries));
        }
    }
}
