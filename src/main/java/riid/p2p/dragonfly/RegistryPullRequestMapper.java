package riid.p2p.dragonfly;

import java.nio.file.Path;
import java.util.Objects;

import ru.hse.dragonfly.puller.registry.RegistryAuth;
import ru.hse.dragonfly.puller.registry.RegistryPullRequest;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;

/**
 * Maps RIID fetch arguments to external registry-based pull request.
 */
public final class RegistryPullRequestMapper {

    private RegistryPullRequestMapper() {
    }

    public static RegistryPullRequest map(RegistryEndpoint endpoint, String repository, ImageDigest digest,
            Path outputPath) {
        return map(endpoint, repository, digest, outputPath, toRegistryAuth(endpoint.credentials()));
    }

    public static RegistryPullRequest map(RegistryEndpoint endpoint, String repository, ImageDigest digest,
            Path outputPath, RegistryAuth auth) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(auth, "auth");
        return new RegistryPullRequest(registryBase(endpoint), repository, null, digest.toString(), auth, outputPath);
    }

    private static String registryBase(RegistryEndpoint endpoint) {
        return endpoint.scheme() + "://" + endpoint.registryName();
    }

    private static RegistryAuth toRegistryAuth(Credentials credentials) {
        if (credentials == null) {
            return RegistryAuth.none();
        }
        return credentials.toRegistryAuth();
    }
}
