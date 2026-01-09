package riid.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Payload supplier for cache writes.
 */
@FunctionalInterface
public interface CachePayload {
    InputStream open() throws IOException;

    default long sizeBytes() {
        return -1;
    }

    static CachePayload of(Path path, long sizeBytes) {
        return new CachePayload() {
            @Override
            public InputStream open() throws IOException {
                return Files.newInputStream(path);
            }

            @Override
            public long sizeBytes() {
                return sizeBytes;
            }
        };
    }
}

