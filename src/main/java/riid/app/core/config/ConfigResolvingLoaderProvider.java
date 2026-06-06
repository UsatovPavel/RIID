package riid.app.core.config;

import java.nio.file.Files;

import io.micrometer.core.instrument.MeterRegistry;

import riid.app.cli.CliApplication;
import riid.app.cli.CliParser;
import riid.app.core.model.ImageId;
import riid.app.daemon.DaemonRuntimeContext;
import riid.app.service.ImageLoadingFacade;
import riid.client.core.config.RegistryEndpoint;
import riid.core.config.ConfigLoader;
import riid.core.config.GlobalConfig;
import riid.core.fs.NioHostFilesystem;
import riid.p2p.P2PExecutor;

/**
 * Resolves config source (file vs built-in defaults) and produces an image loader.
 */
public final class ConfigResolvingLoaderProvider {
    private static final RegistryEndpoint DEFAULT_REGISTRY_ENDPOINT = new RegistryEndpoint("https",
            "registry-1.docker.io", -1, null);

    private ConfigResolvingLoaderProvider() {
    }

    public static CliApplication.ImageLoader create(CliParser.CliOptions options) throws Exception {
        return create(options, null);
    }

    public static CliApplication.ImageLoader create(CliParser.CliOptions options, MeterRegistry meterRegistry)
            throws Exception {
        if (!options.configProvidedByUser() && !Files.exists(options.configPath())) {
            RegistryEndpoint endpoint = options.credentials() == null
                    ? DEFAULT_REGISTRY_ENDPOINT
                    : new RegistryEndpoint(DEFAULT_REGISTRY_ENDPOINT.scheme(), DEFAULT_REGISTRY_ENDPOINT.host(),
                            DEFAULT_REGISTRY_ENDPOINT.port(), options.credentials());
            return defaultLoaderWithBuiltInConfig(endpoint, meterRegistry);
        }
        GlobalConfig config = ConfigLoader.load(options.configPath());
        RegistryEndpoint endpoint = config.client().registries().getFirst();
        if (options.credentials() != null) {
            endpoint = new RegistryEndpoint(endpoint.scheme(), endpoint.host(), endpoint.port(), options.credentials());
        }
        String registry = endpoint.registryName();
        return (repository, reference, runtimeId) -> {
            try (ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(options.configPath(),
                    options.credentials(), meterRegistry)) {
                return facade.load(ImageId.fromRegistry(registry, repository, reference), runtimeId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load image", e);
            }
        };
    }

    public static DaemonRuntimeContext createDaemonRuntime(CliParser.CliOptions options, MeterRegistry meterRegistry)
            throws Exception {
        if (!options.configProvidedByUser() && !Files.exists(options.configPath())) {
            RegistryEndpoint endpoint = options.credentials() == null
                    ? DEFAULT_REGISTRY_ENDPOINT
                    : new RegistryEndpoint(DEFAULT_REGISTRY_ENDPOINT.scheme(), DEFAULT_REGISTRY_ENDPOINT.host(),
                            DEFAULT_REGISTRY_ENDPOINT.port(), options.credentials());
            var fs = new NioHostFilesystem();
            ImageLoadingFacade facade = ImageLoadingFacade.createDefault(endpoint, null, new P2PExecutor.NoOp(),
                    ImageLoadingFacade.defaultRuntimes(), fs, meterRegistry);
            CliApplication.ImageLoader loader = (repository, reference, runtimeId) -> facade
                    .load(ImageId.fromRegistry(endpoint.registryName(), repository, reference), runtimeId);
            return new DaemonRuntimeContext(loader, facade);
        }

        GlobalConfig config = ConfigLoader.load(options.configPath());
        RegistryEndpoint endpoint = config.client().registries().getFirst();
        if (options.credentials() != null) {
            endpoint = new RegistryEndpoint(endpoint.scheme(), endpoint.host(), endpoint.port(), options.credentials());
        }
        String registry = endpoint.registryName();
        ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(options.configPath(), options.credentials(),
                meterRegistry);
        CliApplication.ImageLoader loader = (repository, reference, runtimeId) -> facade
                .load(ImageId.fromRegistry(registry, repository, reference), runtimeId);
        return new DaemonRuntimeContext(loader, facade);
    }

    private static CliApplication.ImageLoader defaultLoaderWithBuiltInConfig(RegistryEndpoint endpoint,
            MeterRegistry meterRegistry) {
        return (repository, reference, runtimeId) -> {
            var fs = new NioHostFilesystem();
            try (ImageLoadingFacade facade = ImageLoadingFacade.createDefault(endpoint, null, new P2PExecutor.NoOp(),
                    ImageLoadingFacade.defaultRuntimes(), fs, meterRegistry)) {
                return facade.load(ImageId.fromRegistry(endpoint.registryName(), repository, reference), runtimeId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load image", e);
            }
        };
    }
}
