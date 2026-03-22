package riid.app.core.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Application-level configuration.
 */
public record AppConfig(
        @JsonProperty("tempDirectory") String tempDirectory,
        @JsonProperty("streamThreads") Integer streamThreads,
        @JsonProperty("allowedRegistries") List<String> allowedRegistries,
        @JsonProperty("daemon") DaemonConfig daemon
) {
    //(EI_EXPOSE_REP*) by spotsbugs
    public AppConfig {
        if (allowedRegistries != null) {
            allowedRegistries = List.copyOf(allowedRegistries);
        }
    }

    @Override
    public List<String> allowedRegistries() {
        return allowedRegistries == null ? List.of() : List.copyOf(allowedRegistries);
    }

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

    public DaemonConfig daemonOrDefault() {
        return daemon == null ? new DaemonConfig(null, null, null, null, null) : daemon;
    }

    public enum OverloadPolicy {
        REJECT
    }

    public record DaemonConfig(
            @JsonProperty("bindHost") String bindHost,
            @JsonProperty("bindPort") Integer bindPort,
            @JsonProperty("maxConcurrentPulls") Integer maxConcurrentPulls,
            @JsonProperty("requestTimeout") Duration requestTimeout,
            @JsonProperty("overloadPolicy") OverloadPolicy overloadPolicy
    ) {
        private static final String DEFAULT_BIND_HOST = "127.0.0.1";
        private static final int DEFAULT_BIND_PORT = 8080;
        private static final int DEFAULT_MAX_CONCURRENT_PULLS = 32;
        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(30);
        private static final OverloadPolicy DEFAULT_OVERLOAD_POLICY = OverloadPolicy.REJECT;

        public String bindHostOrDefault() {
            if (bindHost == null || bindHost.isBlank()) {
                return DEFAULT_BIND_HOST;
            }
            return bindHost;
        }

        public int bindPortOrDefault() {
            if (bindPort == null || bindPort <= 0) {
                return DEFAULT_BIND_PORT;
            }
            return bindPort;
        }

        public int maxConcurrentPullsOrDefault() {
            if (maxConcurrentPulls == null || maxConcurrentPulls <= 0) {
                return DEFAULT_MAX_CONCURRENT_PULLS;
            }
            return maxConcurrentPulls;
        }

        public Duration requestTimeoutOrDefault() {
            if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
                return DEFAULT_REQUEST_TIMEOUT;
            }
            return requestTimeout;
        }

        public OverloadPolicy overloadPolicyOrDefault() {
            return overloadPolicy == null ? DEFAULT_OVERLOAD_POLICY : overloadPolicy;
        }
    }
}

