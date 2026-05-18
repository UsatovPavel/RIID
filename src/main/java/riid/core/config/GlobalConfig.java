package riid.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.app.config.AppConfig;
import riid.client.core.config.ClientConfig;
import riid.dispatcher.core.config.DispatcherConfig;
import riid.runtime.RuntimeConfig;
import riid.p2p.P2PConfig;

/**
 * Global application configuration holder.
 */
public record GlobalConfig(
        @JsonProperty("client") ClientConfig client,
        @JsonProperty("dispatcher") DispatcherConfig dispatcher,
        @JsonProperty("p2p") P2PConfig p2p,
        @JsonProperty("app") AppConfig app,
        @JsonProperty("runtime") RuntimeConfig runtime
) {
}

