package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(
        @JsonProperty("output") OutputConfig output,
        @JsonProperty("dockerCmd") String dockerCmd,
        @JsonProperty("maxTasksCommandExecutor") Integer maxTasksCommandExecutor
) {
    public static final String DEFAULT_DOCKER_BIN = "docker";

    public OutputConfig outputConfigOrDefault() {
        return output == null ? OutputConfig.defaults() : output;
    }

    public String dockerCmdOrDefault() {
        return dockerCmd == null || dockerCmd.isBlank() ? DEFAULT_DOCKER_BIN : dockerCmd;
    }

    public Integer maxTasksCommandExecutor() {
        return maxTasksCommandExecutor;
    }
}


