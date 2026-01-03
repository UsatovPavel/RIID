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

    private Path pathFor(String digest) {
        // sanitize digest -> use as filename
        return root.resolve(digest.replace(':', '_'));
    }

    @Override
    public boolean has(String digest) {
        return Files.exists(pathFor(digest));
    }

    @Override
    public Optional<String> getPath(String digest) {
        Path p = pathFor(digest);
        return Files.exists(p) ? Optional.of(p.toString()) : Optional.empty();
    }

    @Override
    public String put(String digest, InputStream data, long size, String mediaType) throws IOException {
        Path p = pathFor(digest);
        try (var out = new FileOutputStream(p.toFile())) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = data.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        return p.toString();
    }
}
