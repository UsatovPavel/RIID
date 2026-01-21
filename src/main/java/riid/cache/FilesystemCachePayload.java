package riid.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Cache payload backed by a filesystem path.
 */
public final class FilesystemCachePayload implements CachePayload {
    private final Path path;
    private final Long knownSize;

    private FilesystemCachePayload(Path path, Long knownSize) {
        this.path = Objects.requireNonNull(path, "path");
        this.knownSize = knownSize;
    }

    public static FilesystemCachePayload of(Path path) {
        return new FilesystemCachePayload(path, null);
    }

    public static FilesystemCachePayload of(Path path, long sizeBytes) {
        return new FilesystemCachePayload(path, sizeBytes > 0 ? sizeBytes : null);
    }

    @Override
    public InputStream open() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public long sizeBytes() throws IOException {
        if (knownSize != null && knownSize > 0) {
            return knownSize;
        }
        return Files.size(path);
    }
}
