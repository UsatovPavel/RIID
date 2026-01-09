package riid.cache;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Simple filesystem-backed CacheAdapter used for the demo container.
 */
public final class FileCacheAdapter implements CacheAdapter {
    private final Path root;

    public FileCacheAdapter(String root) {
        this.root = Path.of(root);
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory", e);
        }
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
        } catch (IOException ignored) {
            // ignore probe failures, default to UNKNOWN media type
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
}
