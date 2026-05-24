package riid.dispatcher.metrics;

/**
 * Counts layer fetches and payload bytes by logical source (aligned with
 * {@link riid.dispatcher.SimpleRequestDispatcher} order).
 */
public interface DispatcherLayerSourceMetrics {

    DispatcherLayerSourceMetrics NOOP = new DispatcherLayerSourceMetrics() {
        @Override
        public void recordLayerFetch(String source) {
        }

        @Override
        public void recordLayerFetchedBytes(String source, long bytes) {
        }
    };

    /**
     * @param source
     *            one of {@code cache}, {@code p2p}, {@code registry}
     */
    void recordLayerFetch(String source);

    /**
     * Layer payload bytes attributed to this source (Prometheus counter for
     * {@code increase()} / volume dashboards).
     */
    void recordLayerFetchedBytes(String source, long bytes);
}
