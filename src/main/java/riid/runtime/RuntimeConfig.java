package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(
        @JsonProperty("output") OutputConfig output
) {
    public OutputConfig outputConfigOrDefault() {
        return output == null ? OutputConfig.defaults() : output;
    }
}


