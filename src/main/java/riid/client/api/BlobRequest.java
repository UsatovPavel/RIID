package riid.client.api;

/**
 * Request to fetch a blob.
 */
public record BlobRequest(
        String repository,
        String digest,
        Long expectedSize,
        String mediaType
) {
}

