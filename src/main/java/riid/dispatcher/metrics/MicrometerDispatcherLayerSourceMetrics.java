package riid.dispatcher.metrics;

import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Prometheus-compatible counters for dispatcher layer source (cache / P2P / registry).
 */
public final class MicrometerDispatcherLayerSourceMetrics implements DispatcherLayerSourceMetrics {

    private static final String FETCHES = "riid.dispatcher.layer.fetches";
    private static final String BYTES = "riid.dispatcher.layer.bytes";

    private final MeterRegistry registry;

    public MicrometerDispatcherLayerSourceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void recordLayerFetch(String source) {
        Counter.builder(FETCHES)
                .description("Layers served from cache, P2P, or registry")
                .tag("source", source)
                .register(registry)
                .increment();
    }

    @Override
    public void recordLayerFetchedBytes(String source, long bytes) {
        if (bytes <= 0) {
            return;
        }
        Counter.builder(BYTES)
                .description("Layer payload bytes served from cache, P2P, or registry")
                .baseUnit("bytes")
                .tag("source", source)
                .register(registry)
                .increment(bytes);
    }
}
