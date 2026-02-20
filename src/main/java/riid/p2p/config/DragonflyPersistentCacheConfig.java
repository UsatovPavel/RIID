package riid.p2p.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly persistent cache preconditions.
 */
public record DragonflyPersistentCacheConfig(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("schedulerRedisEnabled") Boolean schedulerRedisEnabled
) {
    public DragonflyPersistentCacheConfig() {
        this(false, false);
    }

    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean schedulerRedisEnabledOrDefault() {
        return Boolean.TRUE.equals(schedulerRedisEnabled);
    }
}
