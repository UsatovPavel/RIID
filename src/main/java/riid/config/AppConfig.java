package riid.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.client.core.config.ClientConfig;
import riid.dispatcher.DispatcherConfig;

/**
 * Global application configuration holder.
 */
public record AppConfig(
        @JsonProperty("client") ClientConfig client,
        @JsonProperty("dispatcher") DispatcherConfig dispatcher
) {
}

