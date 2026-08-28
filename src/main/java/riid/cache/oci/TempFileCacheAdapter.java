package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.PathSupport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Temporary filesystem cache, useful for tests and ephemeral runs. Bounded
 * instances evict least-recently-used entries during {@link #put} when
 * projected usage reaches 90%, stopping at 50%.
 */
public final class TempFileCacheAdapter implements CacheAdapter, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TempFileCacheAdapter.class);
    private static final int HIGH_WATERMARK_PERCENT = 90;
    private static final int LOW_WATERMARK_PERCENT = 50;

    private final Path rootPath;
    private final FileCacheAdapter delegate;
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "HostFilesystem is stateless")
    private final HostFilesystem fs;
    private final long maxCacheBytes;
    private final Map<ImageDigest, CacheEntry> entriesByRecency = new LinkedHashMap<>(16, 0.75F, true);
    private long currentCacheBytes;
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
    public synchronized boolean has(ImageDigest digest) {
        boolean present = delegate.has(digest);
        if (!present) {
            forget(digest);
        }
        return present;
    }

    @Override
    public synchronized Optional<CacheEntry> get(ImageDigest digest) {
        Optional<CacheEntry> entry = delegate.get(digest);
        if (entry.isPresent()) {
            remember(entry.orElseThrow());
        } else {
            forget(digest);
        }
        return entry;
    }

    @Override
    public Optional<Path> resolve(String key) {
        return delegate.resolve(key);
    }

    @Override
    public synchronized CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType)
            throws IOException {
        long payloadSize = payload.sizeBytes();
        if (maxCacheBytes > 0 && payloadSize > 0 && payloadSize > maxCacheBytes) {
            throw new IOException(
                    "cache payload exceeds configured maxCacheBytes: " + payloadSize + " > " + maxCacheBytes);
        }
        if (maxCacheBytes > 0 && payloadSize > 0) {
            evictBeforePut(digest, payloadSize);
        }

        CacheEntry entry = delegate.put(digest, payload, mediaType);
        remember(entry);
        if (maxCacheBytes > 0 && entry.sizeBytes() > maxCacheBytes) {
            removeEntry(entry);
            throw new IOException("cache payload exceeds configured maxCacheBytes after write: " + entry.sizeBytes()
                    + " > " + maxCacheBytes);
        }
        if (maxCacheBytes > 0 && currentCacheBytes >= highWatermarkBytes()) {
            evictOldestUntil(lowWatermarkBytes(), digest);
        }
        return entry;
    }

    /**
     * Delete all temp files. Safe to call multiple times.
     */
    public synchronized void cleanup() throws IOException {
        if (cleaned) {
            return;
        }
        fs.deleteRecursively(rootPath);
        entriesByRecency.clear();
        currentCacheBytes = 0L;
        cleaned = true;
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    public Path rootDir() {
        return rootPath;
    }

    private void evictBeforePut(ImageDigest digest, long payloadSize) throws IOException {
        CacheEntry replaced = entriesByRecency.get(digest);
        long replacedBytes = replaced == null ? 0L : replaced.sizeBytes();
        long projectedBytes = currentCacheBytes - replacedBytes + payloadSize;
        if (projectedBytes < highWatermarkBytes()) {
            return;
        }

        long targetBytes = lowWatermarkBytes();
        evictOldestUntilProjected(targetBytes, digest, projectedBytes);
    }

    private void evictOldestUntil(long targetBytes, ImageDigest protectedDigest) throws IOException {
        evictOldestUntilProjected(targetBytes, protectedDigest, currentCacheBytes);
    }

    private void evictOldestUntilProjected(long targetBytes, ImageDigest protectedDigest, long projectedBytes)
            throws IOException {
        long initialProjectedBytes = projectedBytes;
        int evictedEntries = 0;
        Iterator<Map.Entry<ImageDigest, CacheEntry>> iterator = entriesByRecency.entrySet().iterator();
        while (projectedBytes > targetBytes && iterator.hasNext()) {
            Map.Entry<ImageDigest, CacheEntry> candidate = iterator.next();
            if (candidate.getKey().equals(protectedDigest)) {
                continue;
            }
            CacheEntry entry = candidate.getValue();
            Path path = delegate.resolve(entry.key()).orElseThrow(() -> new IOException("Invalid cache entry key"));
            fs.deleteIfExists(path);
            iterator.remove();
            currentCacheBytes -= entry.sizeBytes();
            projectedBytes -= entry.sizeBytes();
            evictedEntries++;
        }
        if (evictedEntries > 0) {
            LOGGER.info("LRU cache eviction removed {} entries ({} bytes); projected usage {} -> {} bytes",
                    evictedEntries, initialProjectedBytes - projectedBytes, initialProjectedBytes, projectedBytes);
        }
    }

    private void remember(CacheEntry entry) {
        CacheEntry previous = entriesByRecency.put(entry.digest(), entry);
        if (previous != null) {
            currentCacheBytes -= previous.sizeBytes();
        }
        currentCacheBytes += entry.sizeBytes();
    }

    private void forget(ImageDigest digest) {
        CacheEntry removed = entriesByRecency.remove(digest);
        if (removed != null) {
            currentCacheBytes -= removed.sizeBytes();
        }
    }

    private void removeEntry(CacheEntry entry) throws IOException {
        Path path = delegate.resolve(entry.key()).orElseThrow(() -> new IOException("Invalid cache entry key"));
        fs.deleteIfExists(path);
        forget(entry.digest());
    }

    private long highWatermarkBytes() {
        return percentage(maxCacheBytes, HIGH_WATERMARK_PERCENT);
    }

    private long lowWatermarkBytes() {
        return percentage(maxCacheBytes, LOW_WATERMARK_PERCENT);
    }

    private static long percentage(long value, int percent) {
        return value / 100L * percent + value % 100L * percent / 100L;
    }

}
