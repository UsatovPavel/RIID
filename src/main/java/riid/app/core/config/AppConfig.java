package riid.app.core.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Application-level configuration.
 */
public record AppConfig(@JsonProperty("tempDirectory") String tempDirectory,
        @JsonProperty("streamThreads") Integer streamThreads,
        @JsonProperty("allowedRegistries") List<String> allowedRegistries,
        @JsonProperty("daemon") DaemonConfig daemon) {
    // (EI_EXPOSE_REP*) by spotsbugs
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
        return daemon == null ? new DaemonConfig(null, null, null, null, null, null, null, null, null, null) : daemon;
    }

    public enum OverloadPolicy {
        REJECT
    }

    public record DaemonConfig(@JsonProperty("unixSocketPath") String unixSocketPath,
            @JsonProperty("metricsHost") String metricsHost, @JsonProperty("metricsPort") Integer metricsPort,
            @JsonProperty("maxConcurrentPulls") Integer maxConcurrentPulls,
            @JsonProperty("maxRequestBodyBytes") Integer maxRequestBodyBytes,
            @JsonProperty("requestTimeout") Duration requestTimeout,
            @JsonProperty("overloadPolicy") OverloadPolicy overloadPolicy,
            @JsonProperty("maxCacheBytes") Long maxCacheBytes,
            @JsonProperty("cacheHighWatermarkPercent") Integer cacheHighWatermarkPercent,
            @JsonProperty("cacheLowWatermarkPercent") Integer cacheLowWatermarkPercent) {
        private static final String DEFAULT_UNIX_SOCKET_PATH = "/tmp/riid.sock";
        private static final String DEFAULT_METRICS_HOST = "0.0.0.0";
        private static final int DEFAULT_METRICS_PORT = 9090;
        private static final int DEFAULT_MAX_CONCURRENT_PULLS = 32;
        private static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 8192;
        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(30);
        private static final OverloadPolicy DEFAULT_OVERLOAD_POLICY = OverloadPolicy.REJECT;
        private static final long DEFAULT_MAX_CACHE_BYTES = 1_073_741_824L;
        private static final int DEFAULT_CACHE_HIGH_WATERMARK_PERCENT = 90;
        private static final int DEFAULT_CACHE_LOW_WATERMARK_PERCENT = 50;

        public String unixSocketPathOrDefault() {
            if (unixSocketPath == null || unixSocketPath.isBlank()) {
                return DEFAULT_UNIX_SOCKET_PATH;
            }
            return unixSocketPath;
        }

        public String metricsHostOrDefault() {
            if (metricsHost == null || metricsHost.isBlank()) {
                return DEFAULT_METRICS_HOST;
            }
            return metricsHost;
        }

        public int metricsPortOrDefault() {
            if (metricsPort == null || metricsPort <= 0) {
                return DEFAULT_METRICS_PORT;
            }
            return metricsPort;
        }

        public int maxConcurrentPullsOrDefault() {
            if (maxConcurrentPulls == null || maxConcurrentPulls <= 0) {
                return DEFAULT_MAX_CONCURRENT_PULLS;
            }
            return maxConcurrentPulls;
        }

        public int maxRequestBodyBytesOrDefault() {
            if (maxRequestBodyBytes == null || maxRequestBodyBytes <= 0) {
                return DEFAULT_MAX_REQUEST_BODY_BYTES;
            }
            return maxRequestBodyBytes;
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

        public long maxCacheBytesOrDefault() {
            if (maxCacheBytes == null || maxCacheBytes <= 0) {
                return DEFAULT_MAX_CACHE_BYTES;
            }
            return maxCacheBytes;
        }

        public int cacheHighWatermarkPercentOrDefault() {
            return cacheHighWatermarkPercent == null
                    ? DEFAULT_CACHE_HIGH_WATERMARK_PERCENT
                    : cacheHighWatermarkPercent;
        }

        public int cacheLowWatermarkPercentOrDefault() {
            return cacheLowWatermarkPercent == null
                    ? DEFAULT_CACHE_LOW_WATERMARK_PERCENT
                    : cacheLowWatermarkPercent;
        }
    }
}
