package riid.client.manifest;

import riid.client.core.protocol.Manifest;

/**
 * Result of manifest fetch.
 */
public record ManifestResult(
        String digest,
        String mediaType,
        long contentLength,
        Manifest manifest
) {
}

