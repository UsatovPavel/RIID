package riid.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.config.AppConfig;
import riid.config.ConfigLoader;
import riid.p2p.P2PExecutor;
import riid.runtime.PodmanRuntimeAdapter;
import riid.runtime.PortoRuntimeAdapter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds ImageLoadService from YAML config; no CLI side effects.
 */
public final class ImageLoadServiceFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadServiceFactory.class);

    private ImageLoadServiceFactory() {}

    public static ImageLoadService createFromConfig(Path configPath) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        AppConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();

        TempFileCacheAdapter cache = new TempFileCacheAdapter();
        Map<String, riid.runtime.RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());

        ImageLoadService app = ImageLoadService.createDefault(endpoint, cache, new P2PExecutor.NoOp(), runtimes);
        LOGGER.info("ImageLoadService initialized with endpoint {}://{}", endpoint.scheme(), endpoint.host());
        return app;
    }
}
