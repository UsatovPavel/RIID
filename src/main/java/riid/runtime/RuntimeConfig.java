package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import riid.runtime.adapter.RuntimeAdapter;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(@JsonProperty("output") OutputConfig output, @JsonProperty("dockerCmd") String dockerCmd,
        @JsonProperty("maxTasksCommandExecutor") Integer maxTasksCommandExecutor,
        @JsonProperty("prefixImportStride") Integer prefixImportStride) {
    public static final String DEFAULT_DOCKER_BIN = "docker";
    /** @see RuntimeAdapter#DEFAULT_PREFIX_IMPORT_STRIDE */
    public static final int DEFAULT_PREFIX_IMPORT_STRIDE = RuntimeAdapter.DEFAULT_PREFIX_IMPORT_STRIDE;

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
     * How many layers the engine is given at a time while the rest still downloads;
     * 0 keeps the single import of the finished image. Applies to the engines with
     * no per-layer import command (podman, containerd); Porto imports layer by
     * layer regardless.
     */
    public int prefixImportStrideOrDefault() {
        return prefixImportStride == null ? DEFAULT_PREFIX_IMPORT_STRIDE : prefixImportStride;
    }
}
