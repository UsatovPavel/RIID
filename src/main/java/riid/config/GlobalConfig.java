package riid.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.app.AppConfig;
import riid.client.core.config.ClientConfig;
import riid.dispatcher.DispatcherConfig;
import riid.runtime.RuntimeConfig;

/**
 * Global application configuration holder.
 */
public record GlobalConfig(
        @JsonProperty("client") ClientConfig client,
        @JsonProperty("dispatcher") DispatcherConfig dispatcher,
        @JsonProperty("app") AppConfig app,
        @JsonProperty("runtime") RuntimeConfig runtime
) {
}

