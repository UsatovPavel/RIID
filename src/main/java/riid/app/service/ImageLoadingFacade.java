package riid.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.app.core.config.AppConfig;
import riid.app.core.model.ImageId;
import riid.app.core.error.AppError;
import riid.app.core.error.AppException;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.app.ociarchive.OciArchiveBuilder;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.AuthConfig;
import riid.client.core.config.BlobPartialDownloadConfig;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.core.config.ConfigLoader;
import riid.core.config.GlobalConfig;
import io.micrometer.core.instrument.MeterRegistry;

import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.dispatcher.metrics.DispatcherLayerSourceMetrics;
import riid.dispatcher.metrics.MicrometerDispatcherLayerSourceMetrics;
import riid.core.logging.MdcContext;
import riid.core.logging.MilestoneEventLogger;
import riid.p2p.dragonfly.DragonflyGrpcP2PExecutor;
import riid.p2p.P2PExecutor;
import riid.runtime.BoundedCommandExecution;
import riid.runtime.DockerRuntimeAdapter;
import riid.runtime.PodmanRuntimeAdapter;
import riid.runtime.PortoRuntimeAdapter;
import riid.runtime.RuntimeAdapter;
import riid.runtime.RuntimeConfig;

/**
 * Application entrypoint/facade: load image (dispatcher -> OCI -> runtime), optionally run.
 * Not a god-class: it wires existing components and delegates real work to them.
 */
public final class ImageLoadingFacade implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadingFacade.class);
    private static final CacheCleaner NOOP_CACHE_CLEANER = () -> {
    };

    private final OciArchiveBuilder archiveBuilder;
    private final RuntimeRegistry runtimeRegistry;
    private final RegistryClient client;
    private final Set<String> allowedRegistries;
    private final CacheCleaner cacheCleaner;

    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs) {
        this(dispatcher, runtimeRegistry, client, fs, null, null, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs,
                              Path tempRoot,
                              List<String> allowedRegistries) {
        this(dispatcher, runtimeRegistry, client, fs, tempRoot, allowedRegistries, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs,
                              Path tempRoot,
                              List<String> allowedRegistries,
                              CacheCleaner cacheCleaner) {
        this.archiveBuilder = new OciArchiveBuilder(dispatcher, fs, tempRoot);
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.client = Objects.requireNonNull(client, "client");
        this.allowedRegistries = allowedRegistries == null
                ? Set.of()
                : Set.copyOf(new HashSet<>(allowedRegistries));
        this.cacheCleaner = cacheCleaner != null ? cacheCleaner : NOOP_CACHE_CLEANER;
    }

    /**
     * High-level load: download/validate, assemble OCI, import into runtime.
     *
     * @return resolved image and tar size (bytes) passed to the runtime
     */
    public LoadOutcome load(ImageId imageId, String runtimeId) {
        Objects.requireNonNull(imageId, "imageId");
        ensureRegistryAllowed(imageId.registry());
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation("manifest.fetch");
        long manifestStartedNs = System.nanoTime();
        ManifestResult manifestResult;
        try {
            MilestoneEventLogger.info(LOGGER)
                    .addEvent("manifest.fetch")
                    .addResult("start")
                    .log("Fetching manifest for " + imageId);
            manifestResult = client.fetchManifest(imageId.name(), imageId.reference());
            MilestoneEventLogger.info(LOGGER)
                    .addEvent("manifest.fetch")
                    .addResult("success")
                    .addDurationMs(durationMs(manifestStartedNs))
                    .log("Manifest fetched");
        } catch (Exception e) {
            MilestoneEventLogger.error(LOGGER)
                    .addCause(e)
                    .addEvent("manifest.fetch")
                    .addResult("error")
                    .addDurationMs(durationMs(manifestStartedNs))
                    .addErrorKind("NETWORK")
                    .addErrorCode("MANIFEST_FETCH_FAILED")
                    .log("Manifest fetch failed");
            throw e;
        } finally {
            MdcContext.restoreOperation(previousOperation);
        }
        RuntimeAdapter runtime = runtimeRegistry.get(runtimeId);
        ImageId resolved = imageId.withDigest(manifestResult.digest());
        return load(manifestResult, runtime, resolved);
    }

    /**
     * Load using prepared manifest result and runtime.
     *
     * @return resolved image and tar size (bytes) passed to the runtime
     */
    public LoadOutcome load(ManifestResult manifestResult, RuntimeAdapter runtime, ImageId imageId) {
        Objects.requireNonNull(manifestResult, "manifestResult");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(imageId, "imageId");
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation("engine.import");
        long engineStartedNs = System.nanoTime();
        try {
            return archiveBuilder.withArchive(imageId, manifestResult, archivePath -> {
                long tarBytes = Files.size(archivePath);
                runtime.importImage(archivePath);
                MilestoneEventLogger.info(LOGGER)
                        .addEvent("engine.import")
                        .addResult("success")
                        .addDurationMs(durationMs(engineStartedNs))
                        .log("Loaded " + imageId + " into runtime " + runtime.runtimeId() + " at " + archivePath);
                return new LoadOutcome(imageId, tarBytes);
            });
        } catch (AppException e) {
            MilestoneEventLogger.error(LOGGER)
                    .addCause(e)
                    .addEvent("engine.import")
                    .addResult("error")
                    .addDurationMs(durationMs(engineStartedNs))
                    .addErrorKind("RUNTIME")
                    .addErrorCode(e.errorCode())
                    .log("App error while loading " + imageId + " into runtime " + runtime.runtimeId()
                            + ": " + e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppError.RuntimeErrorKind errorKind = AppError.RuntimeErrorKind.LOAD_FAILED;
            String msg = errorKind.format(runtime.runtimeId());
            MilestoneEventLogger.error(LOGGER)
                    .addCause(e)
                    .addEvent("engine.import")
                    .addResult("error")
                    .addDurationMs(durationMs(engineStartedNs))
                    .addErrorKind("RUNTIME")
                    .addErrorCode(errorKind.name())
                    .log("Runtime import interrupted");
            throw new AppException(
                    new AppError.RuntimeError(errorKind, msg),
                    msg, e);
        } catch (IOException e) {
            AppError.RuntimeErrorKind errorKind = AppError.RuntimeErrorKind.LOAD_FAILED;
            String msg = errorKind.format(runtime.runtimeId());
            MilestoneEventLogger.error(LOGGER)
                    .addCause(e)
                    .addEvent("engine.import")
                    .addResult("error")
                    .addDurationMs(durationMs(engineStartedNs))
                    .addErrorKind("RUNTIME")
                    .addErrorCode(errorKind.name())
                    .log("Runtime import I/O error");
            throw new AppException(
                    new AppError.RuntimeError(errorKind, msg),
                    msg, e);
        } finally {
            MdcContext.restoreOperation(previousOperation);
        }
    }

    public static ImageLoadingFacade createDefault(RegistryEndpoint endpoint,
                                                   CacheAdapter cache,
                                                   P2PExecutor p2p,
                                                   Map<String, RuntimeAdapter> runtimes,
                                                   HostFilesystem fs) {
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig);
        RequestDispatcher dispatcher = new SimpleRequestDispatcher(client, cache, p2p, fs);
        RuntimeRegistry registry = new RuntimeRegistry(runtimes);
        return new ImageLoadingFacade(dispatcher, registry, client, fs, null, null);
    }

    /**
     * Build ImageLoadingFacade from YAML config.
     */
    public static ImageLoadingFacade createFromConfig(Path configPath) throws Exception {
        return createFromConfig(configPath, null, null);
    }

    public static ImageLoadingFacade createFromConfig(
            Path configPath,
            Credentials credentialsOverride) throws Exception {
        return createFromConfig(configPath, credentialsOverride, null);
    }

    public static ImageLoadingFacade createFromConfig(
            Path configPath,
            Credentials credentialsOverride,
            MeterRegistry meterRegistry) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        GlobalConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();
        if (credentialsOverride != null) {
            endpoint = new RegistryEndpoint(
                    endpoint.scheme(),
                    endpoint.host(),
                    endpoint.port(),
                    credentialsOverride
            );
        }
        HostFilesystem fs = new NioHostFilesystem();
        TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
        HttpClientConfig httpConfig = new HttpClientConfig();
        AuthConfig authConfig = config.client() != null && config.client().auth() != null
                ? config.client().auth()
                : new AuthConfig();
        BlobPartialDownloadConfig blobPartialDownloadConfig =
                config.client() != null ?
                        config.client().partialDownloadingOrDefault() : new BlobPartialDownloadConfig();
        RegistryClient client = new RegistryClientImpl(
                endpoint,
                httpConfig,
                authConfig,
                blobPartialDownloadConfig);

        Map<String, RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());
        RuntimeConfig runtimeConfig = config.runtime();
        String dockerCmd = runtimeConfig != null
                ? runtimeConfig.dockerCmdOrDefault()
                : RuntimeConfig.DEFAULT_DOCKER_BIN;
        runtimes.put("docker", new DockerRuntimeAdapter(fs, null, dockerCmd));

        AppConfig appConfig = config.app();
        if (runtimeConfig != null) {
            BoundedCommandExecution.setDefaultOutputConfig(runtimeConfig.outputConfigOrDefault());
            if (runtimeConfig.maxTasksCommandExecutor() != null) {
                BoundedCommandExecution.setMaxTasksCommandExecutor(runtimeConfig.maxTasksCommandExecutor());
            }
        }
        Path tempDir = appConfig != null ? appConfig.tempDirectoryPath() : null;
        List<String> allowedRegistries = appConfig != null ? appConfig.allowedRegistriesOrEmpty() : List.of();
        P2PExecutor p2p = new P2PExecutor.NoOp();
        if (config.p2p() != null
                && config.p2p().dragonfly() != null
                && config.p2p().dragonfly().enabledOrDefault()) {
            p2p = new DragonflyGrpcP2PExecutor(endpoint, fs, config.p2p().dragonfly());
        }
        DispatcherLayerSourceMetrics layerMetrics = meterRegistry == null
                ? DispatcherLayerSourceMetrics.NOOP
                : new MicrometerDispatcherLayerSourceMetrics(meterRegistry);
        return new ImageLoadingFacade(
                new SimpleRequestDispatcher(client, cache, p2p, config.dispatcher(), fs, layerMetrics),
                new RuntimeRegistry(runtimes),
                client,
                fs,
                tempDir,
                allowedRegistries,
                cache::close);
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

    @Override
    public void close() throws IOException {
        IOException error = null;
        try {
            client.close();
        } catch (Exception e) {
            error = new IOException("Failed to close registry client", e);
        }
        try {
            cacheCleaner.close();
        } catch (Exception e) {
            IOException cacheError = new IOException("Failed to close cache adapter", e);
            if (error == null) {
                error = cacheError;
            } else {
                error.addSuppressed(cacheError);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * Default runtime adapters used by CLI and tests.
     */
    public static Map<String, RuntimeAdapter> defaultRuntimes() {
        Map<String, RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());
        runtimes.put("docker", new DockerRuntimeAdapter());
        return Map.copyOf(runtimes);
    }

    @FunctionalInterface
    public interface CacheCleaner {
        void close() throws Exception;
    }

    private static long durationMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

}


