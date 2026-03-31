package riid.dispatcher.metrics;

import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Prometheus-compatible counters for dispatcher layer source (cache / P2P / registry).
 */
public final class MicrometerDispatcherLayerSourceMetrics implements DispatcherLayerSourceMetrics {

    private static final String METRIC = "riid.dispatcher.layer.fetches";

    private final MeterRegistry registry;

    public MicrometerDispatcherLayerSourceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void recordLayerFetch(String source) {
        Counter.builder(METRIC)
                .description("Layers served from cache, P2P, or registry")
                .tag("source", source)
                .register(registry)
                .increment();
    }
}
