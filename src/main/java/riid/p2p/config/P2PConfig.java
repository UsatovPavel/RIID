package riid.p2p.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * P2P module configuration.
 */
public record P2PConfig(
        @JsonProperty("dragonfly") DragonflyConfig dragonfly
) {
}
