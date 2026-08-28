package riid.cache.oci;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps one cache entry available until the lease is closed.
 */
public final class CacheLease implements AutoCloseable {
    private static final Runnable NOOP = () -> {
    };

    private final CacheEntry entry;
    private final Path path;
    private final Runnable releaser;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CacheLease(CacheEntry entry, Path path, Runnable releaser) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.path = Objects.requireNonNull(path, "path");
        this.releaser = Objects.requireNonNull(releaser, "releaser");
    }

    public static CacheLease managed(CacheEntry entry, Path path, Runnable releaser) {
        return new CacheLease(entry, path, releaser);
    }

    public static CacheLease unmanaged(CacheEntry entry, Path path) {
        return new CacheLease(entry, path, NOOP);
    }

    public CacheEntry entry() {
        return entry;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaser.run();
        }
    }
}
