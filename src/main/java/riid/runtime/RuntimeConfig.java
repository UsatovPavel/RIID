package riid.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import riid.runtime.adapter.ContainerdRuntimeAdapter;
import riid.runtime.adapter.RuntimeAdapter;
import riid.runtime.adapter.RuntimeId;

/**
 * Runtime module configuration.
 */
public record RuntimeConfig(@JsonProperty("output") OutputConfig output, @JsonProperty("dockerCmd") String dockerCmd,
        @JsonProperty("maxTasksCommandExecutor") Integer maxTasksCommandExecutor,
        @JsonProperty("prefixImport") Boolean prefixImport,
        @JsonProperty("containerdSnapshotter") String containerdSnapshotter,
        @JsonProperty("containerdDiscardUnpackedLayers") Boolean containerdDiscardUnpackedLayers) {
    public static final String DEFAULT_DOCKER_BIN = RuntimeId.DOCKER.bin();
    /** @see RuntimeAdapter#PREFIX_IMPORT_ENABLED_BY_DEFAULT */
    public static final boolean DEFAULT_PREFIX_IMPORT = RuntimeAdapter.PREFIX_IMPORT_ENABLED_BY_DEFAULT;

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
     * Whether the engine gets each layer as it lands instead of the whole image at
     * the end. Only for engines with no per-layer import (podman, containerd);
     * Porto imports layer by layer regardless.
     */
    public boolean prefixImportOrDefault() {
        return prefixImport == null ? DEFAULT_PREFIX_IMPORT : prefixImport;
    }

    /**
     * Optional {@code ctr images import} switches for containerd. All off by
     * default, so the emitted command is byte for byte the current one.
     */
    public ContainerdRuntimeAdapter.ImportOptions containerdImportOptions() {
        return new ContainerdRuntimeAdapter.ImportOptions(containerdSnapshotter,
                Boolean.TRUE.equals(containerdDiscardUnpackedLayers));
    }
}
