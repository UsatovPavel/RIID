package riid.client.core.config;

import java.util.Objects;
import java.util.Optional;
import riid.client.http.HttpRequestBuilder;

/**
 * Registry endpoint configuration.
 */
public record RegistryEndpoint(
        String scheme,
        String host,
        int port,
        Credentials credentials
) {
    public RegistryEndpoint {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        if (port < -1) {
            throw new IllegalArgumentException("port must be -1 (default) or non-negative");
        }
    }

    public static RegistryEndpoint https(String host) {
        return new RegistryEndpoint("https", host, -1, null);
    }

    public Optional<Credentials> credentialsOpt() {
        return Optional.ofNullable(credentials);
    }

    public java.net.URI uri(String path) {
        return HttpRequestBuilder.buildUri(scheme, host, port, path);
    }

    public java.net.URI uri(String path, String query) {
        return HttpRequestBuilder.buildUri(scheme, host, port, path, query);
    }

    /**
     * Registry host[:port] for use in image references.
     */
    public String registryName() {
        if (port > 0) {
            return host + ":" + port;
        }
        return host;
    }
}

