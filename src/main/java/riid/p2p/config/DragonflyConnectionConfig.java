package riid.p2p.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dragonfly connection endpoints and binary locations.
 */
public record DragonflyConnectionConfig(
        @JsonProperty("dfgetPath") String dfgetPath,
        @JsonProperty("dfcachePath") String dfcachePath,
        @JsonProperty("daemonEndpoint") String daemonEndpoint,
        @JsonProperty("schedulerAddr") String schedulerAddr
) {
    private static final String DEFAULT_DFGET = "dfget";
    private static final String DEFAULT_DFCACHE = "dfcache";
    private static final String DEFAULT_ENDPOINT = "/tmp/dfdaemon.sock";

    public DragonflyConnectionConfig() {
        this(DEFAULT_DFGET, DEFAULT_DFCACHE, DEFAULT_ENDPOINT, "");
    }

    public String daemonEndpointOrDefault() {
        return daemonEndpoint != null && !daemonEndpoint.isBlank() ? daemonEndpoint : DEFAULT_ENDPOINT;
    }

    public String dfcachePathOrDefault() {
        if (dfcachePath != null && !dfcachePath.isBlank()) {
            return dfcachePath;
        }
        if (dfgetPath != null && !dfgetPath.isBlank() && dfgetPath.endsWith("dfget")) {
            return dfgetPath.substring(0, dfgetPath.length() - "dfget".length()) + "dfcache";
        }
        return DEFAULT_DFCACHE;
    }
}
