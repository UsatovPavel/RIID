package riid.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Interface to an external cache module.
 */
public interface CacheAdapter {
    boolean has(ImageDigest digest);

    Optional<CacheEntry> get(ImageDigest digest);

    /**
     * Store blob stream under digest. Implementation is responsible for closing the stream.
     *
     * @param payload   source of bytes
     * @param mediaType blob media type (typed)
     * @return cache entry/locator (if available)
     */
    CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException;
}

