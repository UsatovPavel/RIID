package riid.cache.oci;

import java.io.IOException;
import java.util.Optional;

/**
 * Interface to an external cache module.
 */
public interface CacheAdapter {
    /**
     * Acquire an existing entry. Its path remains available until the lease closes.
     */
    Optional<CacheLease> acquire(ImageDigest digest);

    /**
     * Store blob stream under digest. Implementation is responsible for closing the
     * stream.
     *
     * @param payload
     *            source of bytes
     * @param mediaType
     *            blob media type (typed)
     * @return a lease for the stored entry
     */
    CacheLease put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException;
}
