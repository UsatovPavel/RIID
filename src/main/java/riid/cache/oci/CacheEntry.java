package riid.cache.oci;

/**
 * Cached blob descriptor.
 */
public record CacheEntry(ImageDigest digest, long sizeBytes, CacheMediaType mediaType, String key) {
}

