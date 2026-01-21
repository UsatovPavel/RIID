package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * Temporary filesystem cache, useful for tests and ephemeral runs.
 */
public final class TempFileCacheAdapter implements CacheAdapter, AutoCloseable {
    private final Path rootPath;
    private final FileCacheAdapter delegate;
    private boolean cleaned;

    public TempFileCacheAdapter() {
        try {
            this.rootPath = Files.createTempDirectory("riid-cache-tmp");
            this.delegate = new FileCacheAdapter(rootPath.toString());
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
        return delegate.put(digest, payload, mediaType);
    }

    /**
     * Delete all temp files. Safe to call multiple times.
     */
    public void cleanup() throws IOException {
        if (cleaned) {
            return;
        }
        deleteRecursively(rootPath);
        cleaned = true;
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    public Path rootDir() {
        return rootPath;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        }
    }
}

