package riid.cache;

import java.io.IOException;
import java.io.InputStream;

/**
 * Payload supplier for cache writes.
 */
public interface CachePayload {

    InputStream open() throws IOException;
    long sizeBytes() throws IOException;
}

