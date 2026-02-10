package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(
        @JsonProperty("output") OutputConfig output,
        @JsonProperty("dockerBin") String dockerBin
) {
    public static final String DEFAULT_DOCKER_BIN = "docker";

    public OutputConfig outputConfigOrDefault() {
        return output == null ? OutputConfig.defaults() : output;
    }

    public String dockerBinOrDefault() {
        return dockerBin == null || dockerBin.isBlank() ? DEFAULT_DOCKER_BIN : dockerBin;
    }
}


