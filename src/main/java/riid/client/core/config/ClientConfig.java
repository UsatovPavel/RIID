package riid.client.core.config;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import riid.client.http.HttpClientConfig;

/**
 * Aggregated client module configuration.
 */
public record ClientConfig(
        @JsonProperty("http") HttpClientConfig http,
        @JsonProperty("auth") AuthConfig auth,
        @JsonProperty("registries") List<RegistryEndpoint> registries,
        @JsonProperty("partialDownloading") BlobPartialDownloadConfig partialDownloading
) {
    public ClientConfig(HttpClientConfig http, AuthConfig auth, List<RegistryEndpoint> registries) {
        this(http, auth, registries, null);
    }

    public ClientConfig {
        if (registries != null) {
            registries = Collections.unmodifiableList(new java.util.ArrayList<>(registries));
        }
    }

    @Override
    public List<RegistryEndpoint> registries() {
        if (registries == null) {
            return java.util.List.of();
        }
        return Collections.unmodifiableList(new java.util.ArrayList<>(registries));
    }

    public boolean registriesMissing() {
        return registries == null;
    }

    public BlobPartialDownloadConfig partialDownloadingOrDefault() {
        return partialDownloading != null ? partialDownloading : new BlobPartialDownloadConfig();
    }
}
