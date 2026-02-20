package riid.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import riid.app.AppConfig;
import riid.client.core.config.ClientConfig;
import riid.dispatcher.DispatcherConfig;
import riid.runtime.RuntimeConfig;
import riid.p2p.config.P2PConfig;

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

