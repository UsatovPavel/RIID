package riid.client.service;

import riid.client.api.ManifestResult;
import riid.client.core.config.RegistryEndpoint;

import java.util.Optional;

/**
 * Manifest service contract.
 */
public interface ManifestServiceApi {
    ManifestResult fetchManifest(RegistryEndpoint endpoint, String repository, String reference, String scope);

    Optional<ManifestResult> headManifest(RegistryEndpoint endpoint, String repository, String reference, String scope);
}

