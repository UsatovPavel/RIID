package riid.cache;

import java.io.File;
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
        String ct = null;
        try {
            ct = Files.probeContentType(p);
        } catch (IOException ignored) {
        }
        return Optional.of(new CacheEntry(digest, p.toFile().length(), CacheMediaType.from(ct), p.toString()));
    }

    @Override
    public CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException {
        Path p = pathFor(digest);
        try (InputStream data = payload.open(); var out = new FileOutputStream(p.toFile())) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = data.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        long size = payload.sizeBytes() > 0 ? payload.sizeBytes() : p.toFile().length();
        return new CacheEntry(digest, size, mediaType, p.toString());
    }
}
