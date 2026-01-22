package riid.config;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Application-level configuration.
 */
public record AppRuntimeConfig(
        @JsonProperty("tempDir") String tempDir,
        @JsonProperty("streamThreads") Integer streamThreads
) {
    public Path tempDirPath() {
        if (tempDir == null || tempDir.isBlank()) {
            return null;
        }
        return Path.of(tempDir);
    }

    public int streamThreadsOrDefault() {
        if (streamThreads == null || streamThreads <= 0) {
            return 2;
        }
        return streamThreads;
    }
}

