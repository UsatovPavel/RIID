package riid.cache.oci;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-digest cache state. All transitions are atomic and never cover file I/O.
 */
final class CacheRecord {
    private final ImageDigest digest;
    private final AtomicReference<AccessState> access = new AtomicReference<>(AccessState.writing());
    private final CompletableFuture<Void> writeFinished = new CompletableFuture<>();
    private final AtomicLong lastAccess = new AtomicLong();
    private volatile CacheEntry entry;
    private volatile Path path;

    CacheRecord(ImageDigest digest) {
        this.digest = digest;
    }

    ImageDigest digest() {
        return digest;
    }

    long sizeBytes() {
        CacheEntry current = entry;
        return current == null ? 0L : current.sizeBytes();
    }

    long lastAccess() {
        return lastAccess.get();
    }

    Path path() {
        return path;
    }

    Phase phase() {
        return access.get().phase();
    }

    boolean isBusy() {
        AccessState current = access.get();
        return current.phase() != Phase.READY || current.readers() > 0;
    }

    /**
     * Atomically add a reader only while the record is ready.
     */
    Optional<CacheLease> acquire(long accessOrder) {
        while (true) {
            AccessState current = access.get();
            if (current.phase() != Phase.READY) {
                return Optional.empty();
            }
            AccessState acquired = current.withReaders(current.readers() + 1);
            if (access.compareAndSet(current, acquired)) {
                lastAccess.set(accessOrder);
                return Optional.of(newLease());
            }
        }
    }

    /**
     * Publish a completed write and retain its first reader in one transition.
     */
    CacheLease publishAndAcquire(CacheEntry publishedEntry, Path publishedPath, long accessOrder) {
        entry = publishedEntry;
        path = publishedPath;
        lastAccess.set(accessOrder);
        AccessState writing = access.get();
        if (writing.phase() != Phase.WRITING) {
            throw new IllegalStateException("Cache record is not writable: " + digest);
        }
        AccessState published = new AccessState(Phase.READY, 1, null);
        if (!access.compareAndSet(writing, published)) {
            throw new IllegalStateException("Cache record is not writable: " + digest);
        }
        writeFinished.complete(null);
        return newLease();
    }

    void failWrite(Throwable failure) {
        access.set(new AccessState(Phase.FAILED, 0, null));
        writeFinished.completeExceptionally(failure);
    }

    CompletableFuture<Void> transitionFinished() {
        AccessState current = access.get();
        if (current.phase() == Phase.WRITING) {
            return writeFinished;
        }
        if (current.phase() == Phase.EVICTING && current.transitionFinished() != null) {
            return current.transitionFinished();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Claim an idle record before deletion; file I/O happens after this CAS.
     */
    EvictionClaim tryStartEviction() {
        while (true) {
            AccessState current = access.get();
            if (current.phase() != Phase.READY || current.readers() != 0) {
                return null;
            }
            CompletableFuture<Void> finished = new CompletableFuture<>();
            AccessState evicting = new AccessState(Phase.EVICTING, 0, finished);
            if (access.compareAndSet(current, evicting)) {
                return new EvictionClaim(evicting, finished);
            }
        }
    }

    void finishEviction(EvictionClaim claim) {
        claim.finished().complete(null);
    }

    void cancelEviction(EvictionClaim claim) {
        if (!access.compareAndSet(claim.state(), new AccessState(Phase.READY, 0, null))) {
            IllegalStateException failure = new IllegalStateException(
                    "Cache eviction state is invalid for " + digest);
            claim.finished().completeExceptionally(failure);
            throw failure;
        }
        claim.finished().complete(null);
    }

    private CacheLease newLease() {
        return CacheLease.managed(entry, path, this::release);
    }

    private void release() {
        while (true) {
            AccessState current = access.get();
            if (current.phase() != Phase.READY || current.readers() <= 0) {
                throw new IllegalStateException("Cache lease state is invalid for " + digest);
            }
            if (access.compareAndSet(current, current.withReaders(current.readers() - 1))) {
                return;
            }
        }
    }

    enum Phase {
        WRITING,
        READY,
        EVICTING,
        FAILED
    }

    record EvictionClaim(AccessState state, CompletableFuture<Void> finished) {
    }

    private record AccessState(Phase phase, int readers, CompletableFuture<Void> transitionFinished) {
        private static AccessState writing() {
            return new AccessState(Phase.WRITING, 0, null);
        }

        private AccessState withReaders(int newReaders) {
            return new AccessState(phase, newReaders, transitionFinished);
        }
    }
}
