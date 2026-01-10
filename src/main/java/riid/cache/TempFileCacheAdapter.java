package riid.cache;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * Temporary filesystem cache, useful for tests and ephemeral runs.
 */
public final class TempFileCacheAdapter implements CacheAdapter, AutoCloseable {
    private final Path root;
    private boolean cleaned;

    public TempFileCacheAdapter() {
        try {
            this.root = Files.createTempDirectory("riid-cache-tmp");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp cache directory", e);
        }
    }

    private Path pathFor(ImageDigest digest) {
        return root.resolve(digest.toString().replace(':', '_'));
    }

    @Override
    public boolean has(ImageDigest digest) {
        return Files.exists(pathFor(digest));
    }

    @Override
    public Optional<CacheEntry> get(ImageDigest digest) {
        Path p = pathFor(digest);
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        String contentType = null;
        try {
            contentType = Files.probeContentType(p);
        } catch (IOException ignored) {
            // ignore probe failures
        }
        long sizeBytes = p.toFile().length();
        CacheMediaType mediaType = CacheMediaType.from(contentType);
        return Optional.of(new CacheEntry(digest, sizeBytes, mediaType, p.toString()));
    }

    @Override
    public CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException {
        Path p = pathFor(digest);
        try (InputStream data = payload.open(); var out = new FileOutputStream(p.toFile())) {
            byte[] buf = new byte[8192];
            while (true) {
                int read = data.read(buf);
                if (read == -1) {
                    break;
                }
                out.write(buf, 0, read);
            }
        }
        long size = payload.sizeBytes() > 0 ? payload.sizeBytes() : p.toFile().length();
        return new CacheEntry(digest, size, mediaType, p.toString());
    }

    /**
     * Delete all temp files. Safe to call multiple times.
     */
    public void cleanup() throws IOException {
        if (cleaned) {
            return;
        }
        if (Files.exists(root)) {
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
        cleaned = true;
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    public Path root() {
        return root;
    }
}

