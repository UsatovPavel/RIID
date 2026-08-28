package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
    private static final int LAST_PIN_COUNT = 1;

    private final Path rootPath;
    private final FileCacheAdapter delegate;
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "HostFilesystem is stateless")
    private final HostFilesystem fs;
    private final long maxCacheBytes;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final Lock mutationLock = new ReentrantLock();
    private final Lock stateLock = new ReentrantLock();
    private final Map<ImageDigest, DigestLock> digestLocks = new ConcurrentHashMap<>();
    private final Map<ImageDigest, CacheEntry> entriesByRecency = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<ImageDigest, Integer> pinsByDigest = new HashMap<>();
    private final CachePressureMetrics pressureMetrics;
    private long currentCacheBytes;
    private boolean cleaned;

    public TempFileCacheAdapter() {
        this(new NioHostFilesystem(), -1L, null);
    }

    public TempFileCacheAdapter(HostFilesystem fs) {
        this(fs, -1L, null);
    }

    public TempFileCacheAdapter(HostFilesystem fs, long maxCacheBytes) {
        this(fs, maxCacheBytes, null);
    }

    public TempFileCacheAdapter(HostFilesystem fs, long maxCacheBytes, MeterRegistry meterRegistry) {
        try {
            this.fs = fs;
            this.maxCacheBytes = maxCacheBytes;
            this.pressureMetrics = new CachePressureMetrics(meterRegistry, maxCacheBytes);
            this.rootPath = PathSupport.tempDirPath("riid-cache-tmp-");
            fs.createDirectory(rootPath);
            this.delegate = new FileCacheAdapter(rootPath.toString(), fs);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp cache directory", e);
        }
    }

    @Override
    public CachePin pin(ImageDigest digest) {
        Objects.requireNonNull(digest, "digest");
        Lock lifecycleReadLock = lifecycleLock.readLock();
        lifecycleReadLock.lock();
        try {
            ensureOpen();
            stateLock.lock();
            try {
                pinsByDigest.merge(digest, 1, Integer::sum);
            } finally {
                stateLock.unlock();
            }
            return new TrackedPin(digest);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    @Override
    public boolean has(ImageDigest digest) {
        Lock lifecycleReadLock = lifecycleLock.readLock();
        lifecycleReadLock.lock();
        try {
            ensureOpen();
            DigestLock digestLock = acquireDigestLock(digest);
            try {
                boolean present = delegate.has(digest);
                if (!present) {
                    stateLock.lock();
                    try {
                        forget(digest);
                    } finally {
                        stateLock.unlock();
                    }
                }
                return present;
            } finally {
                releaseDigestLock(digest, digestLock);
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    @Override
    public Optional<CacheEntry> get(ImageDigest digest) {
        Lock lifecycleReadLock = lifecycleLock.readLock();
        lifecycleReadLock.lock();
        try {
            ensureOpen();
            DigestLock digestLock = acquireDigestLock(digest);
            try {
                Optional<CacheEntry> entry = delegate.get(digest);
                stateLock.lock();
                try {
                    if (entry.isPresent()) {
                        remember(entry.orElseThrow());
                    } else {
                        forget(digest);
                    }
                } finally {
                    stateLock.unlock();
                }
                return entry;
            } finally {
                releaseDigestLock(digest, digestLock);
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    @Override
    public Optional<Path> resolve(String key) {
        Lock lifecycleReadLock = lifecycleLock.readLock();
        lifecycleReadLock.lock();
        try {
            ensureOpen();
            return delegate.resolve(key);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    @Override
    public CacheEntry put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException {
        Lock lifecycleReadLock = lifecycleLock.readLock();
        lifecycleReadLock.lock();
        try {
            ensureOpen();
            if (maxCacheBytes > 0) {
                return putBounded(digest, payload, mediaType);
            }
            return putForDigest(digest, payload, mediaType);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private CacheEntry putBounded(ImageDigest digest, CachePayload payload, CacheMediaType mediaType)
            throws IOException {
        mutationLock.lock();
        try {
            return putForDigest(digest, payload, mediaType);
        } finally {
            mutationLock.unlock();
        }
    }

    private CacheEntry putForDigest(ImageDigest digest, CachePayload payload, CacheMediaType mediaType)
            throws IOException {
        DigestLock digestLock = acquireDigestLock(digest);
        try {
            return putLocked(digest, payload, mediaType);
        } finally {
            releaseDigestLock(digest, digestLock);
        }
    }

    private CacheEntry putLocked(ImageDigest digest, CachePayload payload, CacheMediaType mediaType)
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
        stateLock.lock();
        try {
            remember(entry);
        } finally {
            stateLock.unlock();
        }
        if (maxCacheBytes > 0 && entry.sizeBytes() > maxCacheBytes) {
            removeEntry(entry);
            throw new IOException("cache payload exceeds configured maxCacheBytes after write: " + entry.sizeBytes()
                    + " > " + maxCacheBytes);
        }
        if (maxCacheBytes > 0 && currentUsage() >= highWatermarkBytes()) {
            evictOldestUntil(lowWatermarkBytes(), digest);
        }
        return entry;
    }

    /**
     * Delete all temp files. Safe to call multiple times.
     */
    public void cleanup() throws IOException {
        Lock lifecycleWriteLock = lifecycleLock.writeLock();
        lifecycleWriteLock.lock();
        try {
            if (cleaned) {
                return;
            }
            fs.deleteRecursively(rootPath);
            stateLock.lock();
            try {
                entriesByRecency.clear();
                pinsByDigest.clear();
                currentCacheBytes = 0L;
                pressureMetrics.update(0L, highWatermarkBytes());
                cleaned = true;
            } finally {
                stateLock.unlock();
            }
        } finally {
            lifecycleWriteLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    public Path rootDir() {
        return rootPath;
    }

    private void evictBeforePut(ImageDigest digest, long payloadSize) throws IOException {
        long projectedBytes = projectedUsage(digest, payloadSize);
        if (projectedBytes < highWatermarkBytes()) {
            return;
        }

        evictOldestUntilProjected(lowWatermarkBytes(), digest, payloadSize);
    }

    private void evictOldestUntil(long targetBytes, ImageDigest protectedDigest) throws IOException {
        evictOldestUntilProjected(targetBytes, protectedDigest, 0L);
    }

    private void evictOldestUntilProjected(long targetBytes, ImageDigest protectedDigest, long incomingBytes)
            throws IOException {
        long initialProjectedBytes = projectedUsage(protectedDigest, incomingBytes);
        long finalProjectedBytes = initialProjectedBytes;
        long evictedBytes = 0L;
        int evictedEntries = 0;
        while (finalProjectedBytes > targetBytes) {
            CacheEntry candidate = oldestCandidate(protectedDigest);
            if (candidate == null) {
                break;
            }

            DigestLock candidateLock = acquireDigestLock(candidate.digest());
            try {
                stateLock.lock();
                try {
                    finalProjectedBytes = projectedUsageLocked(protectedDigest, incomingBytes);
                    CacheEntry currentCandidate = oldestCandidateLocked(protectedDigest);
                    if (finalProjectedBytes <= targetBytes) {
                        continue;
                    }
                    if (currentCandidate == null || !currentCandidate.digest().equals(candidate.digest())) {
                        continue;
                    }
                    Path path = delegate.resolve(currentCandidate.key())
                            .orElseThrow(() -> new IOException("Invalid cache entry key"));
                    fs.deleteIfExists(path);
                    entriesByRecency.remove(currentCandidate.digest());
                    currentCacheBytes -= currentCandidate.sizeBytes();
                    pressureMetrics.update(currentCacheBytes, highWatermarkBytes());
                    evictedBytes += currentCandidate.sizeBytes();
                    evictedEntries++;
                    finalProjectedBytes = projectedUsageLocked(protectedDigest, incomingBytes);
                } finally {
                    stateLock.unlock();
                }
            } finally {
                releaseDigestLock(candidate.digest(), candidateLock);
            }
        }
        finalProjectedBytes = projectedUsage(protectedDigest, incomingBytes);
        if (evictedEntries > 0) {
            LOGGER.info("LRU cache eviction removed {} entries ({} bytes); projected usage {} -> {} bytes",
                    evictedEntries, evictedBytes, initialProjectedBytes, finalProjectedBytes);
        }
        logIncompleteEviction(targetBytes, finalProjectedBytes);
    }

    private CacheEntry oldestCandidate(ImageDigest protectedDigest) {
        stateLock.lock();
        try {
            return oldestCandidateLocked(protectedDigest);
        } finally {
            stateLock.unlock();
        }
    }

    private CacheEntry oldestCandidateLocked(ImageDigest protectedDigest) {
        for (CacheEntry entry : entriesByRecency.values()) {
            if (!entry.digest().equals(protectedDigest) && !pinsByDigest.containsKey(entry.digest())) {
                return entry;
            }
        }
        return null;
    }

    private long projectedUsage(ImageDigest protectedDigest, long incomingBytes) {
        stateLock.lock();
        try {
            return projectedUsageLocked(protectedDigest, incomingBytes);
        } finally {
            stateLock.unlock();
        }
    }

    private long projectedUsageLocked(ImageDigest protectedDigest, long incomingBytes) {
        CacheEntry replaced = findEntry(protectedDigest);
        long replacedBytes = incomingBytes > 0 && replaced != null ? replaced.sizeBytes() : 0L;
        return currentCacheBytes - replacedBytes + incomingBytes;
    }

    private CacheEntry findEntry(ImageDigest digest) {
        for (CacheEntry entry : entriesByRecency.values()) {
            if (entry.digest().equals(digest)) {
                return entry;
            }
        }
        return null;
    }

    private long currentUsage() {
        stateLock.lock();
        try {
            return currentCacheBytes;
        } finally {
            stateLock.unlock();
        }
    }

    private void remember(CacheEntry entry) {
        CacheEntry previous = entriesByRecency.put(entry.digest(), entry);
        if (previous != null) {
            currentCacheBytes -= previous.sizeBytes();
        }
        currentCacheBytes += entry.sizeBytes();
        pressureMetrics.update(currentCacheBytes, highWatermarkBytes());
    }

    private void forget(ImageDigest digest) {
        CacheEntry removed = entriesByRecency.remove(digest);
        if (removed != null) {
            currentCacheBytes -= removed.sizeBytes();
            pressureMetrics.update(currentCacheBytes, highWatermarkBytes());
        }
    }

    private void removeEntry(CacheEntry entry) throws IOException {
        Path path = delegate.resolve(entry.key()).orElseThrow(() -> new IOException("Invalid cache entry key"));
        fs.deleteIfExists(path);
        stateLock.lock();
        try {
            forget(entry.digest());
        } finally {
            stateLock.unlock();
        }
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

    private DigestLock acquireDigestLock(ImageDigest digest) {
        DigestLock digestLock = digestLocks.compute(digest, (key, existing) -> {
            DigestLock result = existing == null ? new DigestLock() : existing;
            result.users++;
            return result;
        });
        digestLock.lock.lock();
        return digestLock;
    }

    private void releaseDigestLock(ImageDigest digest, DigestLock digestLock) {
        digestLock.lock.unlock();
        digestLocks.computeIfPresent(digest, (key, existing) -> {
            existing.users--;
            return existing.users == 0 ? null : existing;
        });
    }

    private void ensureOpen() {
        if (cleaned) {
            throw new IllegalStateException("Temporary cache has already been cleaned up");
        }
    }

    private void logIncompleteEviction(long targetBytes, long projectedBytes) {
        if (projectedBytes <= targetBytes) {
            return;
        }
        int pinnedEntries = pinnedEntryCount();
        if (projectedBytes > highWatermarkBytes()) {
            LOGGER.error(
                    "Cache usage remains above high watermark after LRU eviction: projected={} bytes, high={} "
                            + "bytes, low={} bytes, pinnedEntries={}",
                    projectedBytes, highWatermarkBytes(), targetBytes, pinnedEntries);
            return;
        }
        LOGGER.warn("LRU cache eviction stopped above low watermark: projected={} bytes, low={} bytes, "
                + "pinnedEntries={}", projectedBytes, targetBytes, pinnedEntries);
    }

    private int pinnedEntryCount() {
        stateLock.lock();
        try {
            int count = 0;
            for (ImageDigest digest : pinsByDigest.keySet()) {
                if (findEntry(digest) != null) {
                    count++;
                }
            }
            return count;
        } finally {
            stateLock.unlock();
        }
    }

    private void releasePin(ImageDigest digest) {
        stateLock.lock();
        try {
            Integer count = pinsByDigest.get(digest);
            if (count == null) {
                return;
            }
            if (count == LAST_PIN_COUNT) {
                pinsByDigest.remove(digest);
            } else {
                pinsByDigest.put(digest, count - 1);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private static final class DigestLock {
        private final Lock lock = new ReentrantLock(true);
        private int users;
    }

    private final class TrackedPin implements CachePin {
        private final ImageDigest digest;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackedPin(ImageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            releasePin(digest);
        }
    }

    private static final class CachePressureMetrics {
        private static final String USAGE_BYTES = "riid.cache.usage.bytes";
        private static final String LIMIT_BYTES = "riid.cache.limit.bytes";
        private static final String HIGH_WATERMARK_ALERT = "riid.cache.high.watermark.alert";
        private static final String HIGH_WATERMARK_BREACHES = "riid.cache.high.watermark.breaches";

        private final AtomicLong usageBytes = new AtomicLong();
        private final AtomicInteger highWatermarkAlert = new AtomicInteger();
        private final Counter highWatermarkBreaches;

        private CachePressureMetrics(MeterRegistry registry, long maxCacheBytes) {
            if (registry == null || maxCacheBytes <= 0) {
                highWatermarkBreaches = null;
                return;
            }
            Gauge.builder(USAGE_BYTES, usageBytes, AtomicLong::get).description("Current RIID blob cache usage")
                    .baseUnit("bytes").register(registry);
            Gauge.builder(LIMIT_BYTES, () -> maxCacheBytes).description("Configured RIID blob cache limit")
                    .baseUnit("bytes").register(registry);
            Gauge.builder(HIGH_WATERMARK_ALERT, highWatermarkAlert, AtomicInteger::get)
                    .description("1 when RIID blob cache usage is above the 90 percent high watermark")
                    .register(registry);
            highWatermarkBreaches = Counter.builder(HIGH_WATERMARK_BREACHES)
                    .description("Transitions above the RIID blob cache high watermark").register(registry);
        }

        private void update(long currentBytes, long highWatermarkBytes) {
            usageBytes.set(currentBytes);
            int alert = currentBytes > highWatermarkBytes ? 1 : 0;
            int previous = highWatermarkAlert.getAndSet(alert);
            if (alert == 1 && previous == 0 && highWatermarkBreaches != null) {
                highWatermarkBreaches.increment();
            }
        }
    }

}
