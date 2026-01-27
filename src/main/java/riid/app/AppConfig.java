package riid.app;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Application-level configuration.
 */
public record AppConfig(
        @JsonProperty("tempDirectory") String tempDirectory,
        @JsonProperty("streamThreads") Integer streamThreads,
        @JsonProperty("allowedRegistries") List<String> allowedRegistries
) {
    public Path tempDirectoryPath() {
        if (tempDirectory == null || tempDirectory.isBlank()) {
            return null;
        }
        return Path.of(tempDirectory);
    }

    public List<String> allowedRegistriesOrEmpty() {
        return allowedRegistries == null ? List.of() : allowedRegistries;
    }

    public int streamThreadsOrDefault() {
        if (streamThreads == null || streamThreads <= 0) {
            return 2;
        }
        return streamThreads;
    }
}

