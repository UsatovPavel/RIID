package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.PathSupport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Temporary filesystem cache, useful for tests and ephemeral runs.
 */
public final class TempFileCacheAdapter implements CacheAdapter, AutoCloseable {
    private final Path rootPath;
    private final FileCacheAdapter delegate;
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "HostFilesystem is stateless")
    private final HostFilesystem fs;
    private final long maxCacheBytes;
    private boolean cleaned;

    public TempFileCacheAdapter() {
        this(new NioHostFilesystem(), -1L);
    }

    public TempFileCacheAdapter(HostFilesystem fs) {
        this(fs, -1L);
    }

    public TempFileCacheAdapter(HostFilesystem fs, long maxCacheBytes) {
        try {
            this.fs = fs;
            this.maxCacheBytes = maxCacheBytes;
            this.rootPath = PathSupport.tempDirPath("riid-cache-tmp-");
            fs.createDirectory(rootPath);
            this.delegate = new FileCacheAdapter(rootPath.toString(), fs);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp cache directory", e);
        }
    }

    @Override
    public boolean has(ImageDigest digest) {
        return delegate.has(digest);
    }

    @Override
    public Optional<CacheEntry> get(ImageDigest digest) {
        return delegate.get(digest);
    }

    @Override
    public Optional<Path> resolve(String key) {
        return delegate.resolve(key);
    }

    @Override
    public CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException {
        long payloadSize = payload.sizeBytes();
        if (maxCacheBytes > 0 && payloadSize > 0 && payloadSize > maxCacheBytes) {
            throw new IOException(
                    "cache payload exceeds configured maxCacheBytes: " + payloadSize + " > " + maxCacheBytes);
        }
        if (maxCacheBytes > 0 && payloadSize > 0) {
            long used = currentCacheBytes();
            if (used + payloadSize > maxCacheBytes) {
                throw new IOException(
                        "cache quota exceeded: used=" + used + ", payload=" + payloadSize + ", limit=" + maxCacheBytes);
            }
        }

        CacheEntry entry = delegate.put(digest, payload, mediaType);
        if (maxCacheBytes > 0 && currentCacheBytes() > maxCacheBytes) {
            resolve(entry.key()).ifPresent(path -> {
                try {
                    fs.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of just written file
                }
            });
            throw new IOException("cache quota exceeded after write, entry rolled back");
        }
        return entry;
    }

    /**
     * Delete all temp files. Safe to call multiple times.
     */
    public void cleanup() throws IOException {
        if (cleaned) {
            return;
        }
        fs.deleteRecursively(rootPath);
        cleaned = true;
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    public Path rootDir() {
        return rootPath;
    }

    private long currentCacheBytes() throws IOException {
        long total = 0L;
        try (Stream<Path> files = fs.walk(rootPath)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (fs.isRegularFile(path)) {
                    total += fs.size(path);
                }
            }
        }
        return total;
    }

}
