package riid.cache;

/**
 * Cached blob descriptor.
 */
public record CacheEntry(ImageDigest digest, long sizeBytes, CacheMediaType mediaType, String locator) {}

