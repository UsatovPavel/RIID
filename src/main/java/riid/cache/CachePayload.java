package riid.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Payload supplier for cache writes.
 */
public interface CachePayload {
    InputStream open() throws IOException;
    long sizeBytes() throws IOException;
}

