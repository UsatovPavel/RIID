package riid.cache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.PathSupport;

/**
 * Simple filesystem-backed CacheAdapter used for the demo container.
 */
public final class FileCacheAdapter implements CacheAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileCacheAdapter.class);

    private final Path root;
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "HostFilesystem is stateless")
    private final HostFilesystem fs;

    public FileCacheAdapter(String root) throws IOException {
        this(root, new NioHostFilesystem());
    }

    public FileCacheAdapter(String root, HostFilesystem fs) throws IOException {
        this.root = Path.of(root);
        this.fs = fs;
        fs.createDirectory(this.root);
    }

    private Path pathFor(ImageDigest digest) {
        // sanitize digest -> use as filename
        return root.resolve(digest.toString().replace(':', '_'));
    }

    @Override
    public boolean has(ImageDigest digest) {
        return fs.exists(pathFor(digest));
    }

    @Override
    public Optional<CacheEntry> get(ImageDigest digest) {
        Path p = pathFor(digest);
        if (!fs.exists(p)) {
            return Optional.empty();
        }
        String contentType = null;
        try {
            contentType = fs.probeContentType(p);
        } catch (IOException probeError) {
            LOGGER.debug("Failed to probe content type for {}: {}", p, probeError.getMessage());
        }
        long sizeBytes;
        try {
            sizeBytes = fs.size(p);
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
        Path temp = PathSupport.tempPath(root, "cache-", ".tmp");
        fs.createFile(temp);
        try (InputStream data = payload.open();
             OutputStream out = new BufferedOutputStream(fs.newOutputStream(temp))) {
            data.transferTo(out);
        } catch (IOException ex) {
            fs.deleteIfExists(temp);
            throw ex;
        }
        long size = payload.sizeBytes() > 0 ? payload.sizeBytes() : fs.size(temp);
        fs.atomicMove(temp, target);
        String key = root.relativize(target).toString();
        return new CacheEntry(digest, size, mediaType, key);
    }
}
