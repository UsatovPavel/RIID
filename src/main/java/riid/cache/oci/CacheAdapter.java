package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Interface to an external cache module.
 */
public interface CacheAdapter {
    boolean has(ImageDigest digest);

    Optional<CacheEntry> get(ImageDigest digest);

    /**
     * Resolve cache entry key to an absolute path.
     */
    Optional<Path> resolve(String key);

    /**
     * Store blob stream under digest. Implementation is responsible for closing the stream.
     *
     * @param payload   source of bytes
     * @param mediaType blob media type (typed)
     * @return cache entry/locator (if available)
     */
    CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException;
}

