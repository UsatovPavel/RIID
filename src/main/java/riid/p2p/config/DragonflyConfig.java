package riid.p2p.config;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly (dfget) configuration.
 */
public record DragonflyConfig(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("connection") DragonflyConnectionConfig connection,
        @JsonProperty("request") DragonflyRequestConfig request,
        @JsonProperty("persistentCache") DragonflyPersistentCacheConfig persistentCache,
        @JsonProperty("health") DragonflyHealthConfig health
) {
    public DragonflyConfig {
        connection = connection != null ? connection : new DragonflyConnectionConfig();
        request = request != null ? request : new DragonflyRequestConfig();
        persistentCache = persistentCache != null ? persistentCache : new DragonflyPersistentCacheConfig();
        health = health != null ? health : new DragonflyHealthConfig();
    }

    public DragonflyConfig(
            Boolean enabled,
            String dfgetPath,
            String schedulerAddr,
            Duration requestTimeout,
            Integer maxRetries
    ) {
        this(
                enabled,
                new DragonflyConnectionConfig(dfgetPath, null, null, schedulerAddr),
                new DragonflyRequestConfig(requestTimeout, maxRetries, null, null, List.of()),
                null,
                null);
    }

    public DragonflyConfig(
            Boolean enabled,
            String dfgetPath,
            String schedulerAddr,
            Duration requestTimeout,
            Integer maxRetries,
            String daemonEndpoint
    ) {
        this(
                enabled,
                new DragonflyConnectionConfig(dfgetPath, null, daemonEndpoint, schedulerAddr),
                new DragonflyRequestConfig(requestTimeout, maxRetries, null, null, List.of()),
                null,
                null);
    }

    public boolean enabledOrDefault() {
        return enabled != null && enabled;
    }
}
