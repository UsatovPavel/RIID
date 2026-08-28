package riid.cache.oci;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.PathSupport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Temporary filesystem cache. Bounded instances evict LRU entries from the
 * 90 percent high watermark down to the 50 percent low watermark.
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
    private final ConcurrentHashMap<ImageDigest, CacheRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong currentCacheBytes = new AtomicLong();
    private final AtomicLong reservedEvictionBytes = new AtomicLong();
    private final AtomicLong accessSequence = new AtomicLong();
    private final AtomicInteger activeOperations = new AtomicInteger();
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.OPEN);
    private boolean cleaned;

    public TempFileCacheAdapter() {
        this(new NioHostFilesystem(), -1L);
    }

    public TempFileCacheAdapter(HostFilesystem fs) {
        this(fs, -1L);
    }

    /**
     * Create a temporary cache; a non-positive limit leaves it unbounded.
     */
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
    public Optional<CacheLease> acquire(ImageDigest digest) {
        enterOperation();
        try {
            CacheRecord record = records.get(digest);
            if (record == null) {
                return Optional.empty();
            }
            Optional<CacheLease> acquired = record.acquire(nextAccess());
            if (acquired.isEmpty()) {
                return Optional.empty();
            }
            CacheLease lease = acquired.orElseThrow();
            if (fs.exists(lease.path())) {
                return Optional.of(lease);
            }
            lease.close();
            retireMissingRecord(record);
            return Optional.empty();
        } finally {
            leaveOperation();
        }
    }

    @Override
    public CacheLease put(ImageDigest digest, CachePayload payload, CacheMediaType mediaType) throws IOException {
        long declaredSize = payload.sizeBytes();
        rejectOversized(declaredSize);
        enterOperation();
        try {
            while (true) {
                CacheRecord candidate = new CacheRecord(digest);
                CacheRecord record = records.putIfAbsent(digest, candidate);
                if (record == null) {
                    return writeAndPublish(candidate, payload, mediaType);
                }

                Optional<CacheLease> acquired = record.acquire(nextAccess());
                if (acquired.isPresent()) {
                    return acquired.orElseThrow();
                }

                if (record.phase() == CacheRecord.Phase.FAILED) {
                    records.remove(digest, record);
                    continue;
                }
                awaitTransition(record);
            }
        } finally {
            leaveOperation();
        }
    }

    /**
     * Delete all temporary files when no operation or lease is active.
     */
    public synchronized void cleanup() throws IOException {
        if (cleaned) {
            return;
        }
        Lifecycle current = lifecycle.get();
        if (current == Lifecycle.OPEN && !lifecycle.compareAndSet(Lifecycle.OPEN, Lifecycle.CLOSING)) {
            throw new IOException("Cache lifecycle changed while closing");
        }
        if (current == Lifecycle.CLOSED) {
            return;
        }
        if (activeOperations.get() != 0 || records.values().stream().anyMatch(CacheRecord::isBusy)) {
            lifecycle.set(Lifecycle.OPEN);
            throw new IOException("Cannot clean cache while operations or leases are active");
        }

        try {
            fs.deleteRecursively(rootPath);
            records.clear();
            currentCacheBytes.set(0L);
            reservedEvictionBytes.set(0L);
            cleaned = true;
            lifecycle.set(Lifecycle.CLOSED);
        } catch (IOException e) {
            lifecycle.set(Lifecycle.CLOSE_FAILED);
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    public Path rootDir() {
        return rootPath;
    }

    private CacheLease writeAndPublish(CacheRecord record, CachePayload payload, CacheMediaType mediaType)
            throws IOException {
        Path storedPath = null;
        boolean accounted = false;
        CacheLease result;
        try (CacheLease stored = delegate.put(record.digest(), payload, mediaType)) {
            CacheEntry entry = stored.entry();
            storedPath = stored.path();
            rejectOversized(entry.sizeBytes());
            currentCacheBytes.addAndGet(entry.sizeBytes());
            accounted = true;
            result = record.publishAndAcquire(entry, storedPath, nextAccess());
        } catch (IOException | RuntimeException e) {
            records.remove(record.digest(), record);
            if (accounted) {
                currentCacheBytes.addAndGet(-record.sizeBytes());
            }
            if (storedPath != null) {
                deleteAfterFailedWrite(storedPath);
            }
            record.failWrite(e);
            throw e;
        }
        evictIfNeeded();
        return result;
    }

    /**
     * Claim LRU records under CAS, then delete files without a global lock.
     */
    private void evictIfNeeded() {
        if (maxCacheBytes <= 0 || currentCacheBytes.get() < highWatermarkBytes()) {
            return;
        }

        long initialBytes = currentCacheBytes.get();
        long evictedBytes = 0L;
        int evictedEntries = 0;
        List<CacheRecord> candidates = new ArrayList<>(records.values());
        candidates.sort(Comparator.comparingLong(CacheRecord::lastAccess));
        for (CacheRecord candidate : candidates) {
            if (effectiveUsage() <= lowWatermarkBytes()) {
                break;
            }
            CacheRecord.EvictionClaim claim = candidate.tryStartEviction();
            if (claim == null) {
                continue;
            }
            long size = candidate.sizeBytes();
            if (!reserveEviction(size)) {
                candidate.cancelEviction(claim);
                break;
            }
            try {
                fs.deleteIfExists(candidate.path());
                if (records.remove(candidate.digest(), candidate)) {
                    currentCacheBytes.addAndGet(-size);
                    evictedBytes += size;
                    evictedEntries++;
                }
                candidate.finishEviction(claim);
            } catch (IOException e) {
                candidate.cancelEviction(claim);
                LOGGER.warn("Failed to evict cache entry {}: {}", candidate.digest(), e.getMessage());
            } finally {
                reservedEvictionBytes.addAndGet(-size);
            }
        }

        long finalBytes = currentCacheBytes.get();
        if (evictedEntries > 0) {
            LOGGER.info("LRU cache eviction removed {} entries ({} bytes); usage {} -> {} bytes", evictedEntries,
                    evictedBytes, initialBytes, finalBytes);
        }
        logIncompleteEviction(finalBytes);
    }

    /**
     * Prevent concurrent cleaners from jointly evicting past the low watermark.
     */
    private boolean reserveEviction(long size) {
        while (true) {
            long reserved = reservedEvictionBytes.get();
            if (currentCacheBytes.get() - reserved <= lowWatermarkBytes()) {
                return false;
            }
            if (reservedEvictionBytes.compareAndSet(reserved, reserved + size)) {
                return true;
            }
        }
    }

    private long effectiveUsage() {
        return currentCacheBytes.get() - reservedEvictionBytes.get();
    }

    private void retireMissingRecord(CacheRecord record) {
        CacheRecord.EvictionClaim claim = record.tryStartEviction();
        if (claim == null) {
            return;
        }
        if (records.remove(record.digest(), record)) {
            currentCacheBytes.addAndGet(-record.sizeBytes());
        }
        record.finishEviction(claim);
    }

    private void awaitTransition(CacheRecord record) {
        try {
            record.transitionFinished().join();
        } catch (RuntimeException ignored) {
            // The failed writer removes its record. This caller retries and may
            // become the writer for the same digest with its own payload.
        }
    }

    private void rejectOversized(long payloadSize) throws IOException {
        if (maxCacheBytes > 0 && payloadSize > maxCacheBytes) {
            throw new IOException(
                    "cache payload exceeds configured maxCacheBytes: " + payloadSize + " > " + maxCacheBytes);
        }
    }

    private void deleteAfterFailedWrite(Path path) {
        try {
            fs.deleteIfExists(path);
        } catch (IOException cleanupError) {
            LOGGER.warn("Failed to delete unpublished cache entry {}: {}", path, cleanupError.getMessage());
        }
    }

    private void enterOperation() {
        if (lifecycle.get() != Lifecycle.OPEN) {
            throw new IllegalStateException("Temporary cache is not open");
        }
        activeOperations.incrementAndGet();
        if (lifecycle.get() != Lifecycle.OPEN) {
            activeOperations.decrementAndGet();
            throw new IllegalStateException("Temporary cache is closing");
        }
    }

    private void leaveOperation() {
        activeOperations.decrementAndGet();
    }

    private long nextAccess() {
        return accessSequence.incrementAndGet();
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

    private void logIncompleteEviction(long usageBytes) {
        if (usageBytes <= lowWatermarkBytes()) {
            return;
        }
        long activeRecords = records.values().stream().filter(CacheRecord::isBusy).count();
        if (usageBytes > highWatermarkBytes()) {
            LOGGER.error("Cache usage remains above high watermark after LRU eviction: usage={} bytes, high={} "
                    + "bytes, low={} bytes, activeRecords={}", usageBytes, highWatermarkBytes(), lowWatermarkBytes(),
                    activeRecords);
            return;
        }
        LOGGER.warn("LRU cache eviction stopped above low watermark: usage={} bytes, low={} bytes, activeRecords={}",
                usageBytes, lowWatermarkBytes(), activeRecords);
    }

    private enum Lifecycle {
        OPEN,
        CLOSING,
        CLOSE_FAILED,
        CLOSED
    }
}
