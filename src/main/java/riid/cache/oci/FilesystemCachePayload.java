package riid.cache.oci;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

/**
 * Cache payload backed by a filesystem path.
 */
public final class FilesystemCachePayload implements CachePayload {
    private final Path path;
    private final Long knownSize;
    private final HostFilesystem fs;

    private FilesystemCachePayload(HostFilesystem fs, Path path, Long knownSize) {
        this.fs = Objects.requireNonNull(fs, "fs");
        this.path = Objects.requireNonNull(path, "path");
        this.knownSize = knownSize;
    }

    public static FilesystemCachePayload of(Path path) {
        return new FilesystemCachePayload(new NioHostFilesystem(), path, null);
    }

    public static FilesystemCachePayload of(HostFilesystem fs, Path path) {
        return new FilesystemCachePayload(fs, path, null);
    }

    public static FilesystemCachePayload of(Path path, long sizeBytes) {
        return new FilesystemCachePayload(new NioHostFilesystem(), path, sizeBytes > 0 ? sizeBytes : null);
    }

    public static FilesystemCachePayload of(HostFilesystem fs, Path path, long sizeBytes) {
        return new FilesystemCachePayload(fs, path, sizeBytes > 0 ? sizeBytes : null);
    }

    @Override
    public InputStream open() throws IOException {
        return fs.newInputStream(path);
    }

    @Override
    public long sizeBytes() throws IOException {
        if (knownSize != null && knownSize > 0) {
            return knownSize;
        }
        return fs.size(path);
    }
}
