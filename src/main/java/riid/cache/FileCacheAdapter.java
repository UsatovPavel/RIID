package riid.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Simple filesystem-backed CacheAdapter used for the demo container.
 */
public final class FileCacheAdapter implements CacheAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileCacheAdapter.class);

    private final Path root;

    public FileCacheAdapter(String root) throws IOException {
        this.root = Path.of(root);
            Files.createDirectories(this.root);
    }

    private Path pathFor(ImageDigest digest) {
        // sanitize digest -> use as filename
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
        } catch (IOException probeError) {
            LOGGER.debug("Failed to probe content type for {}: {}", p, probeError.getMessage());
        }
        long sizeBytes;
        try {
            sizeBytes = Files.size(p);
        } catch (IOException e) {
            LOGGER.warn("Failed to read size for cache entry {}: {}", p, e.getMessage());
            return Optional.empty();
        }
        CacheMediaType mediaType;
        try {
            mediaType = CacheMediaType.from(contentType);
        } catch (IllegalArgumentException iae) {
            mediaType = CacheMediaType.UNKNOWN;
        }
        String key = root.relativize(p).toString();
        return Optional.of(new CacheEntry(digest, sizeBytes, mediaType, key));
    }

    @Override
    public Optional<Path> resolve(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(root.resolve(key));
    }

    @Override
    public CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException {
        Path target = pathFor(digest);
        Path temp = Files.createTempFile(root, "cache-", ".tmp");
        try (InputStream data = payload.open();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
            data.transferTo(out);
        } catch (IOException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
        long size = payload.sizeBytes() > 0 ? payload.sizeBytes() : Files.size(temp);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.warn("Atomic move not supported, falling back to regular move for {}", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String key = root.relativize(target).toString();
        return new CacheEntry(digest, size, mediaType, key);
    }
}
