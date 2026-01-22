package riid.config;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Application-level configuration.
 */
public record AppRuntimeConfig(
        @JsonProperty("tempDir") String tempDir
) {
    public Path tempDirPath() {
        if (tempDir == null || tempDir.isBlank()) {
            return null;
        }
        return Path.of(tempDir);
    }
}

