package riid.dispatcher.metrics;

/**
 * Counts layer fetches by logical source (aligned with {@link riid.dispatcher.SimpleRequestDispatcher} order).
 */
@FunctionalInterface
public interface DispatcherLayerSourceMetrics {

    DispatcherLayerSourceMetrics NOOP = source -> {
    };

    /**
     * @param source one of {@code cache}, {@code p2p}, {@code registry}
     */
    void recordLayerFetch(String source);
}
