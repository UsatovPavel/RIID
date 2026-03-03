package riid.app.config;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.app.CliApplication;
import riid.app.CliParser;
import riid.app.ImageId;
import riid.app.ImageLoadingFacade;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.core.config.ConfigLoader;
import riid.core.config.GlobalConfig;
import riid.core.fs.NioHostFilesystem;
import riid.core.logging.StructuredLog;
import riid.p2p.P2PExecutor;

/**
 * Resolves config source and creates image loading service.
 */
public final class ConfigResolvingServiceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigResolvingServiceProvider.class);
    private static final RegistryEndpoint DEFAULT_REGISTRY_ENDPOINT =
            new RegistryEndpoint("https", "registry-1.docker.io", -1, null);

    private ConfigResolvingServiceProvider() {
    }

    public static CliApplication.ImageLoader create(CliParser.CliOptions options) throws Exception {
        long started = System.nanoTime();
        if (!options.configProvidedByUser() && !Files.exists(options.configPath())) {
            RegistryEndpoint endpoint = applyCredentials(DEFAULT_REGISTRY_ENDPOINT, options.credentials());
            StructuredLog.info(
                    LOGGER,
                    "config.resolve",
                    "app",
                    "serviceFactory",
                    "success",
                    elapsedMs(started),
                    null,
                    null,
                    StructuredLog.fields(
                            "config_source", "built_in_defaults",
                            "config_path", options.configPath().toString()
                    )
            );
            return defaultLoaderWithBuiltInConfig(endpoint);
        }
        try {
            GlobalConfig config = ConfigLoader.load(options.configPath());
            RegistryEndpoint endpoint = config.client().registries().getFirst();
            endpoint = applyCredentials(endpoint, options.credentials());
            String registry = endpoint.registryName();
            StructuredLog.info(
                    LOGGER,
                    "config.resolve",
                    "app",
                    "serviceFactory",
                    "success",
                    elapsedMs(started),
                    null,
                    null,
                    StructuredLog.fields(
                            "config_source", options.configProvidedByUser() ? "explicit_yaml" : "default_yaml",
                            "config_path", options.configPath().toString()
                    )
            );
            return (repository, reference, runtimeId) -> {
                try (ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(
                        options.configPath(),
                        options.credentials()
                )) {
                    return facade.load(
                            ImageId.fromRegistry(registry, repository, reference),
                            runtimeId
                    ).toString();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load image", e);
                }
            };
        } catch (Exception e) {
            StructuredLog.error(
                    LOGGER,
                    "config.resolve",
                    "app",
                    "serviceFactory",
                    "error",
                    elapsedMs(started),
                    "CONFIG_RESOLVE_FAILED",
                    e.getClass().getSimpleName(),
                    StructuredLog.fields(
                            "config_source", options.configProvidedByUser() ? "explicit_yaml" : "default_yaml",
                            "config_path", options.configPath().toString()
                    )
            );
            throw e;
        }
    }

    private static RegistryEndpoint applyCredentials(RegistryEndpoint endpoint, Credentials credentials) {
        if (credentials == null) {
            return endpoint;
        }
        return new RegistryEndpoint(
                endpoint.scheme(),
                endpoint.host(),
                endpoint.port(),
                credentials
        );
    }

    private static CliApplication.ImageLoader defaultLoaderWithBuiltInConfig(RegistryEndpoint endpoint) {
        return (repository, reference, runtimeId) -> {
            var fs = new NioHostFilesystem();
            try (ImageLoadingFacade facade = ImageLoadingFacade.createDefault(
                    endpoint,
                    null,
                    new P2PExecutor.NoOp(),
                    ImageLoadingFacade.defaultRuntimes(),
                    fs
            )) {
                return facade.load(
                        ImageId.fromRegistry(endpoint.registryName(), repository, reference),
                        runtimeId
                ).toString();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load image", e);
            }
        };
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
