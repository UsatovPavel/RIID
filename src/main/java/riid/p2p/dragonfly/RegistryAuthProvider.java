package riid.p2p.dragonfly;

import java.util.Objects;

import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import ru.hse.dragonfly.puller.registry.RegistryAuth;

/**
 * Resolves effective registry auth for a Dragonfly pull request.
 */
@FunctionalInterface
public interface RegistryAuthProvider {
    RegistryAuth resolve(RegistryEndpoint endpoint, String repository);

    static RegistryAuthProvider passthrough() {
        return (endpoint, repository) -> {
            Objects.requireNonNull(endpoint, "endpoint");
            Credentials credentials = endpoint.credentials();
            return credentials == null ? RegistryAuth.none() : credentials.toRegistryAuth();
        };
    }
}
