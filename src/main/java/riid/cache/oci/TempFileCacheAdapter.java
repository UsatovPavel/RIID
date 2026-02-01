package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.PathSupport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Temporary filesystem cache, useful for tests and ephemeral runs.
 */
public final class TempFileCacheAdapter implements CacheAdapter, AutoCloseable {
    private final Path rootPath;
    private final FileCacheAdapter delegate;
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "HostFilesystem is stateless")
    private final HostFilesystem fs;
    private boolean cleaned;

    public TempFileCacheAdapter() {
        this(new NioHostFilesystem());
    }

    public TempFileCacheAdapter(HostFilesystem fs) {
        try {
            this.fs = fs;
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
        return delegate.put(digest, payload, mediaType);
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

}

