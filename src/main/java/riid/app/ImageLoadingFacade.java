package riid.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.app.error.AppError;
import riid.app.error.AppException;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.ociarchive.OciArchiveBuilder;
import riid.cache.CacheAdapter;
import riid.cache.TempFileCacheAdapter;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.config.GlobalConfig;
import riid.config.ConfigLoader;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.p2p.P2PExecutor;
import riid.runtime.BoundedCommandExecution;
import riid.runtime.PodmanRuntimeAdapter;
import riid.runtime.PortoRuntimeAdapter;
import riid.runtime.RuntimeAdapter;
import riid.runtime.RuntimeConfig;

/**
 * Application entrypoint/facade: load image (dispatcher -> OCI -> runtime), optionally run.
 * Not a god-class: it wires existing components and delegates real work to them.
 */
public final class ImageLoadingFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadingFacade.class);

    private final OciArchiveBuilder archiveBuilder;
    private final RuntimeRegistry runtimeRegistry;
    private final RegistryClient client;
    private final Set<String> allowedRegistries;
    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs) {
        this(dispatcher, runtimeRegistry, client, fs, null, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs,
                              Path tempRoot,
                              List<String> allowedRegistries) {
        this.archiveBuilder = new OciArchiveBuilder(dispatcher, fs, tempRoot);
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.client = Objects.requireNonNull(client, "client");
        this.allowedRegistries = allowedRegistries == null
                ? Set.of()
                : Set.copyOf(new HashSet<>(allowedRegistries));
    }

    /**
     * High-level load: download/validate, assemble OCI, import into runtime.
     *
     * @return resolved ImageId used for runtime
     */
    public ImageId load(ImageId imageId, String runtimeId) {
        Objects.requireNonNull(imageId, "imageId");
        ensureRegistryAllowed(imageId.registry());
        ManifestResult manifestResult = client.fetchManifest(imageId.name(), imageId.reference());
            RuntimeAdapter runtime = runtimeRegistry.get(runtimeId);
        ImageId resolved = imageId.withDigest(manifestResult.digest());
        return load(manifestResult, runtime, resolved);
    }

    /**
     * Load using prepared manifest result and runtime.
     *
     * @return resolved ImageId used for runtime
     */
    public ImageId load(ManifestResult manifestResult, RuntimeAdapter runtime, ImageId imageId) {
        Objects.requireNonNull(manifestResult, "manifestResult");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(imageId, "imageId");
        try {
            return archiveBuilder.withArchive(imageId, manifestResult, archivePath -> {
                runtime.importImage(archivePath);
                LOGGER.info("Loaded {} into runtime {} at {}", imageId, runtime.runtimeId(), archivePath);
                return imageId;
            });
        } catch (AppException e) {
            LOGGER.error("App error while loading {} into runtime {}: {}",
                    imageId, runtime.runtimeId(), e.getMessage(), e);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String msg = AppError.RuntimeErrorKind.LOAD_FAILED.format(runtime.runtimeId());
            throw new AppException(
                    new AppError.RuntimeError(AppError.RuntimeErrorKind.LOAD_FAILED, msg),
                    msg, e);
        } catch (IOException e) {
            String msg = AppError.RuntimeErrorKind.LOAD_FAILED.format(runtime.runtimeId());
            throw new AppException(
                    new AppError.RuntimeError(AppError.RuntimeErrorKind.LOAD_FAILED, msg),
                    msg, e);
        }
    }

    public static ImageLoadingFacade createDefault(RegistryEndpoint endpoint,
                                                   CacheAdapter cache,
                                                   P2PExecutor p2p,
                                                   Map<String, RuntimeAdapter> runtimes,
                                                   HostFilesystem fs) {
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, cache);
        RequestDispatcher dispatcher = new SimpleRequestDispatcher(client, cache, p2p, fs);
        RuntimeRegistry registry = new RuntimeRegistry(runtimes);
        return new ImageLoadingFacade(dispatcher, registry, client, fs, null, null);
    }

    /**
     * Build ImageLoadFacade from YAML config.
     */
    public static ImageLoadingFacade createFromConfig(Path configPath) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        GlobalConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();
        TempFileCacheAdapter cache = new TempFileCacheAdapter();
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, cache);

        Map<String, RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());

        AppConfig appConfig = config.app();
        RuntimeConfig runtimeConfig = config.runtime();
        if (runtimeConfig != null) {
            BoundedCommandExecution.setMaxOutputBytes(runtimeConfig.maxOutputBytesOrDefault());
        }
        Path tempDir = appConfig != null ? appConfig.tempDirectoryPath() : null;
        HostFilesystem fs = new NioHostFilesystem();
        List<String> allowedRegistries = appConfig != null ? appConfig.allowedRegistriesOrEmpty() : List.of();
        return new ImageLoadingFacade(
                new SimpleRequestDispatcher(client, cache, new P2PExecutor.NoOp(), fs),
                new RuntimeRegistry(runtimes),
                client,
                fs,
                tempDir,
                allowedRegistries);
    }

    private void ensureRegistryAllowed(String registry) {
        if (allowedRegistries.isEmpty()) {
            return;
        }
        if (!allowedRegistries.contains(registry)) {
            String msg = AppError.RuntimeErrorKind.REGISTRY_NOT_ALLOWED.format(registry);
            throw new AppException(
                    new AppError.RuntimeError(AppError.RuntimeErrorKind.REGISTRY_NOT_ALLOWED, msg),
                    msg);
        }
    }

}


