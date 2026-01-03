package riid.client.blob;

/**
 * Result of blob fetch.
 */
public record BlobResult(
        String digest,
        long size,
        String mediaType,
        String path
) {
}

