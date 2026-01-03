package riid.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Interface to an external cache module.
 */
public interface CacheAdapter {
    boolean has(String digest);

    Optional<String> getPath(String digest);

    /**
     * Store blob stream under digest.
     *
     * @return path to stored blob (if available)
     */
    String put(String digest, InputStream data, long size, String mediaType) throws IOException;
}

