package riid.app.service;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.NioHostFilesystem;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.dispatcher.metrics.DispatcherLayerSourceMetrics;
import riid.dispatcher.metrics.MicrometerDispatcherLayerSourceMetrics;
import riid.p2p.P2PExecutor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLoadingFacadeDefaultMetricsTest {

    @Test
    void createDefaultWithMeterRegistryWiresDispatcherSourceMetrics() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (ImageLoadingFacade facade = ImageLoadingFacade.createDefault(
                new RegistryEndpoint("https", "registry-1.docker.io", -1, null),
                null,
                new P2PExecutor.NoOp(),
                ImageLoadingFacade.defaultRuntimes(),
                new NioHostFilesystem(),
                registry)) {
            DispatcherLayerSourceMetrics layerMetrics = extractLayerMetrics(facade);
            assertInstanceOf(MicrometerDispatcherLayerSourceMetrics.class, layerMetrics);

            layerMetrics.recordLayerFetch("registry");
            layerMetrics.recordLayerFetchedBytes("registry", 123L);

            String scrape = registry.scrape();
            assertTrue(scrape.contains("riid_dispatcher_layer_fetches_total"), scrape);
            assertTrue(scrape.contains("riid_dispatcher_layer_bytes_total"), scrape);
        }
    }

    private static DispatcherLayerSourceMetrics extractLayerMetrics(ImageLoadingFacade facade) throws Exception {
        Field archiveBuilderField = ImageLoadingFacade.class.getDeclaredField("archiveBuilder");
        archiveBuilderField.setAccessible(true);
        Object archiveBuilder = archiveBuilderField.get(facade);

        Field dispatcherField = archiveBuilder.getClass().getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);
        Object dispatcher = dispatcherField.get(archiveBuilder);
        assertInstanceOf(SimpleRequestDispatcher.class, dispatcher);

        Field layerMetricsField = SimpleRequestDispatcher.class.getDeclaredField("layerSourceMetrics");
        layerMetricsField.setAccessible(true);
        return (DispatcherLayerSourceMetrics) layerMetricsField.get(dispatcher);
    }
}
