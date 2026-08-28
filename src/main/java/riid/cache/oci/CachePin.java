package riid.cache.oci;

/**
 * Scoped protection against LRU eviction for one cache digest. Explicit cache
 * cleanup remains destructive.
 */
@FunctionalInterface
public interface CachePin extends AutoCloseable {
    CachePin NOOP = () -> {
    };

    @Override
    void close();
}
