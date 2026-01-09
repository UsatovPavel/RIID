package riid.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.DispatcherConfig;
import riid.client.core.config.RegistryEndpoint;

import java.util.List;

/**
 * Global application configuration holder.
 */
public record AppConfig(
        @JsonProperty("http") HttpClientConfig http,
        @JsonProperty("dispatcher") DispatcherConfig dispatcher,
        @JsonProperty("registries") List<RegistryEndpoint> registries
) {
}

