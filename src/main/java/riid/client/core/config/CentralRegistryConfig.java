package riid.client.core.config;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration holder for Central Registry endpoints available to the client/dispatcher.
 */
public final class CentralRegistryConfig {
    private final List<RegistryEndpoint> endpoints;

    public CentralRegistryConfig(List<RegistryEndpoint> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        this.endpoints = List.copyOf(endpoints);
    }

    public List<RegistryEndpoint> endpoints() {
        return Collections.unmodifiableList(endpoints);
    }
}

