package riid.client.api;

import riid.core.model.manifest.Manifest;

/**
 * Result of manifest fetch.
 */
public record ManifestResult(String digest, String mediaType, long contentLength, Manifest manifest) {
}
