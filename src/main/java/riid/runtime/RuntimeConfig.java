package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(@JsonProperty("output") OutputConfig output, @JsonProperty("dockerCmd") String dockerCmd,
        @JsonProperty("maxTasksCommandExecutor") Integer maxTasksCommandExecutor,
        @JsonProperty("podmanPrefixImportStride") Integer podmanPrefixImportStride) {
    public static final String DEFAULT_DOCKER_BIN = "docker";
    /** Prefix import off: podman gets the whole image in one import, as before. */
    public static final int DEFAULT_PODMAN_PREFIX_IMPORT_STRIDE = 0;

    public OutputConfig outputConfigOrDefault() {
        return output == null ? OutputConfig.defaults() : output;
    }

    public String dockerCmdOrDefault() {
        return dockerCmd == null || dockerCmd.isBlank() ? DEFAULT_DOCKER_BIN : dockerCmd;
    }

    public Integer maxTasksCommandExecutor() {
        return maxTasksCommandExecutor;
    }

    /**
     * How many layers podman is given at a time while the rest still downloads; 0
     * keeps the single import of the finished image.
     */
    public int podmanPrefixImportStrideOrDefault() {
        return podmanPrefixImportStride == null ? DEFAULT_PODMAN_PREFIX_IMPORT_STRIDE : podmanPrefixImportStride;
    }
}
