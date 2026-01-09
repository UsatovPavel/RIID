package riid.client.core.config;

import java.util.Objects;
import java.util.Optional;

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
}

