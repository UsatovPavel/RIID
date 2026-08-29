package riid.dispatcher.model;

import java.nio.file.Path;

import riid.cache.oci.CacheLease;
import riid.cache.oci.ImageDigest;
import riid.core.model.manifest.MediaType;

/**
 * Result of image fetch orchestration. Closing it releases a cache-backed path.
 */
public final class FetchResult implements AutoCloseable {
    private final ImageDigest digest;
    private final MediaType mediaType;
    private final Path path;
    private final CacheLease cacheLease;

    public FetchResult(ImageDigest digest, MediaType mediaType, Path path) {
        this(digest, mediaType, path, null);
    }

    /**
     * Create a result that owns the supplied cache lease.
     */
    public static FetchResult leased(ImageDigest digest, MediaType mediaType, CacheLease cacheLease) {
        return new FetchResult(digest, mediaType, cacheLease.path(), cacheLease);
    }

    private FetchResult(ImageDigest digest, MediaType mediaType, Path path, CacheLease cacheLease) {
        this.digest = digest;
        this.mediaType = mediaType;
        this.path = path;
        this.cacheLease = cacheLease;
    }

    public ImageDigest digest() {
        return digest;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    public Path path() {
        return path;
    }

    /**
     * Copy result metadata without transferring ownership of the cache lease.
     */
    public FetchResult detached() {
        return new FetchResult(digest, mediaType, path);
    }

    @Override
    public void close() {
        if (cacheLease != null) {
            cacheLease.close();
        }
    }
}
