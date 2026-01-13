package riid.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.config.AppConfig;
import riid.config.ConfigLoader;
import riid.p2p.P2PExecutor;
import riid.runtime.PodmanRuntimeAdapter;
import riid.runtime.PortoRuntimeAdapter;
import riid.runtime.RuntimeAdapter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds ImageLoadService from YAML config; no CLI side effects.
 */
public final class ImageLoadServiceFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadServiceFactory.class);

    private ImageLoadServiceFactory() {}

    @SuppressWarnings("PMD.CloseResource") // cache lifecycle is managed by the returned service
    public static ImageLoadService createFromConfig(Path configPath) throws Exception {
        return createFromConfig(configPath, null);
    }

    @SuppressWarnings("PMD.CloseResource") // cache lifecycle is managed by the returned service
    public static ImageLoadService createFromConfig(Path configPath,
                                                    riid.client.core.config.Credentials credentialsOverride) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        AppConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();
        if (credentialsOverride != null) {
            endpoint = new RegistryEndpoint(
                    endpoint.scheme(),
                    endpoint.host(),
                    endpoint.port(),
                    credentialsOverride
            );
        }

        TempFileCacheAdapter cache = new TempFileCacheAdapter();
        Map<String, RuntimeAdapter> runtimes = new HashMap<>(defaultRuntimes());

        long ttl = config.client().auth().defaultTokenTtlSeconds();
        ImageLoadService app = ImageLoadService.createDefault(
                endpoint, cache, new P2PExecutor.NoOp(), runtimes, ttl);
        LOGGER.info("ImageLoadService initialized with endpoint {}://{}", endpoint.scheme(), endpoint.host());
        return app;
    }

    /**
     * Default runtime adapters used by the CLI and factory.
     */
    public static Map<String, RuntimeAdapter> defaultRuntimes() {
        Map<String, RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());
        return Map.copyOf(runtimes);
    }
}
