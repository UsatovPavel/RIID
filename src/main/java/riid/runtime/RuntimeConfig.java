package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(
        @JsonProperty("maxOutputBytes") Integer maxOutputBytes
) {
    public int maxOutputBytesOrDefault() {
        if (maxOutputBytes == null || maxOutputBytes <= 0) {
            return BoundedCommandExecution.DEFAULT_MAX_OUTPUT_BYTES;
        }
        return maxOutputBytes;
    }
}


