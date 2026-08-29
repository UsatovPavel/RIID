package riid.app.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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
import riid.core.model.manifest.Descriptor;
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
import riid.dispatcher.core.config.DispatcherConfig;
import riid.dispatcher.metrics.DispatcherLayerSourceMetrics;
import riid.dispatcher.metrics.MicrometerDispatcherLayerSourceMetrics;
import riid.core.logging.MdcContext;
import riid.core.logging.MilestoneEventLogger;
import riid.p2p.dragonfly.ChallengeTokenAuthProvider;
import riid.p2p.dragonfly.DragonflyGrpcP2PExecutor;
import riid.p2p.P2PExecutor;
import riid.runtime.BoundedCommandExecution;
import riid.runtime.adapter.ContainerdRuntimeAdapter;
import riid.runtime.adapter.DockerRuntimeAdapter;
import riid.runtime.adapter.ImageReference;
import riid.runtime.adapter.IncrementalImageImport;
import riid.runtime.adapter.PodmanRuntimeAdapter;
import riid.runtime.adapter.PortoRuntimeAdapter;
import riid.runtime.adapter.RuntimeAdapter;
import riid.runtime.adapter.RuntimeId;
import riid.runtime.RuntimeConfig;
import riid.core.logging.MilestoneEventLogger.EventType;
import riid.core.logging.MilestoneEventLogger.ResultType;

/**
 * Application entrypoint/facade: load image (dispatcher -> OCI -> runtime),
 * optionally run. Not a god-class: it wires existing components and delegates
 * real work to them.
 */
public final class ImageLoadingFacade implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadingFacade.class);
    private static final CacheCleaner NOOP_CACHE_CLEANER = () -> {
    };
    private static final CacheCleaner NOOP_P2P_CLEANER = () -> {
    };
    /** nanoTime never returns this, so it is a safe "not yet" marker. */
    private static final long HANDOVER_NOT_STARTED = Long.MIN_VALUE;

    private static final String LOADED = "Loaded ";
    private static final String PAYLOAD_SUFFIX = " B payload)";
    private static final String INTO_RUNTIME = " into runtime ";
    private final OciArchiveBuilder archiveBuilder;
    private final RuntimeRegistry runtimeRegistry;
    private final RegistryClient client;
    private final Set<String> allowedRegistries;
    private final CacheCleaner cacheCleaner;
    private final CacheCleaner p2pCleaner;

    public ImageLoadingFacade(RequestDispatcher dispatcher, RuntimeRegistry runtimeRegistry, RegistryClient client,
            HostFilesystem fs) {
        this(dispatcher, runtimeRegistry, client, fs, null, null, null, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher, RuntimeRegistry runtimeRegistry, RegistryClient client,
            HostFilesystem fs, Path tempRoot, List<String> allowedRegistries) {
        this(dispatcher, runtimeRegistry, client, fs, tempRoot, allowedRegistries, null, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher, RuntimeRegistry runtimeRegistry, RegistryClient client,
            HostFilesystem fs, Path tempRoot, List<String> allowedRegistries, CacheCleaner cacheCleaner) {
        this(dispatcher, runtimeRegistry, client, fs, tempRoot, allowedRegistries, cacheCleaner, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher, RuntimeRegistry runtimeRegistry, RegistryClient client,
            HostFilesystem fs, Path tempRoot, List<String> allowedRegistries, CacheCleaner cacheCleaner,
            CacheCleaner p2pCleaner) {
        this.archiveBuilder = new OciArchiveBuilder(dispatcher, fs, tempRoot);
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.client = Objects.requireNonNull(client, "client");
        this.allowedRegistries = allowedRegistries == null ? Set.of() : Set.copyOf(new HashSet<>(allowedRegistries));
        this.cacheCleaner = cacheCleaner != null ? cacheCleaner : NOOP_CACHE_CLEANER;
        this.p2pCleaner = p2pCleaner != null ? p2pCleaner : NOOP_P2P_CLEANER;
    }

    /**
     * High-level load: download/validate, assemble OCI, import into runtime.
     *
     * @return resolved image and payload size (bytes) passed to metrics
     */
    public LoadOutcome load(ImageId imageId, RuntimeId runtimeId) {
        Objects.requireNonNull(imageId, "imageId");
        ensureRegistryAllowed(imageId.registry());
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation(EventType.MANIFEST_FETCH.value());
        long manifestStartedNs = System.nanoTime();
        ManifestResult manifestResult;
        try {
            manifestResult = client.fetchManifest(imageId.name(), imageId.reference());
            MilestoneEventLogger.info(LOGGER).addEvent(EventType.MANIFEST_FETCH).addResult(ResultType.SUCCESS)
                    .addDurationMs(durationMs(manifestStartedNs)).log("Manifest fetched");
        } catch (Exception e) {
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.MANIFEST_FETCH)
                    .addResult(ResultType.ERROR).addDurationMs(durationMs(manifestStartedNs)).addErrorKind("NETWORK")
                    .addErrorCode("MANIFEST_FETCH_FAILED").log("Manifest fetch failed");
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
     * @return resolved image and payload size (bytes) passed to metrics
     */
    public LoadOutcome load(ManifestResult manifestResult, RuntimeAdapter runtime, ImageId imageId) {
        Objects.requireNonNull(manifestResult, "manifestResult");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(imageId, "imageId");
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation(EventType.LOAD_TOTAL.value());
        long loadStartedNs = System.nanoTime();
        try {
            long payloadBytes = archiveBuilder.estimatePayloadBytes(manifestResult);
            LoadOutcome outcome;
            if (runtime.supportsIncrementalImport(manifestResult.manifest())) {
                long handoverStartedNs = importIncrementally(manifestResult, runtime, imageId);
                MilestoneEventLogger.info(LOGGER).addEvent(EventType.ENGINE_IMPORT).addResult(ResultType.SUCCESS)
                        .addDurationMs(durationMs(handoverStartedNs)).log(LOADED + imageId + INTO_RUNTIME
                                + runtime.runtimeId() + " layer by layer (~" + payloadBytes + PAYLOAD_SUFFIX);
                outcome = new LoadOutcome(imageId, payloadBytes);
            } else if (runtime.prefersOciLayoutStreamImport()) {
                outcome = archiveBuilder.withOciLayout(imageId, manifestResult, ociDir -> {
                    long importStartedNs = System.nanoTime();
                    runtime.importOciLayoutDirectory(ociDir);
                    MilestoneEventLogger.info(LOGGER).addEvent(EventType.ENGINE_IMPORT)
                            .addResult(ResultType.SUCCESS).addDurationMs(durationMs(importStartedNs))
                            .log(LOADED + imageId + INTO_RUNTIME + runtime.runtimeId()
                                    + " via OCI layout stream (~" + payloadBytes + PAYLOAD_SUFFIX);
                    return new LoadOutcome(imageId, payloadBytes);
                });
            } else {
                outcome = archiveBuilder.withArchive(imageId, manifestResult, archivePath -> {
                    long importStartedNs = System.nanoTime();
                    runtime.importImage(archivePath);
                    MilestoneEventLogger.info(LOGGER).addEvent(EventType.ENGINE_IMPORT)
                            .addResult(ResultType.SUCCESS).addDurationMs(durationMs(importStartedNs))
                            .log(LOADED + imageId + INTO_RUNTIME + runtime.runtimeId() + " at " + archivePath);
                    return new LoadOutcome(imageId, payloadBytes);
                });
            }
            MilestoneEventLogger.info(LOGGER).addEvent(EventType.LOAD_TOTAL).addResult(ResultType.SUCCESS)
                    .addDurationMs(durationMs(loadStartedNs))
                    .log(LOADED + imageId + INTO_RUNTIME + runtime.runtimeId() + " (~" + payloadBytes
                            + PAYLOAD_SUFFIX);
            return outcome;
        } catch (AppException e) {
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.LOAD_TOTAL).addResult(ResultType.ERROR)
                    .addDurationMs(durationMs(loadStartedNs)).addErrorKind("RUNTIME").addErrorCode(e.errorCode())
                    .log("App error while loading " + imageId + INTO_RUNTIME + runtime.runtimeId() + ": "
                            + e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppError.RuntimeErrorKind errorKind = AppError.RuntimeErrorKind.LOAD_FAILED;
            String msg = errorKind.format(runtime.runtimeId());
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.LOAD_TOTAL).addResult(ResultType.ERROR)
                    .addDurationMs(durationMs(loadStartedNs)).addErrorKind("RUNTIME").addErrorCode(errorKind.name())
                    .log("Runtime import interrupted");
            throw new AppException(new AppError.RuntimeError(errorKind, msg), msg, e);
        } catch (IOException e) {
            AppError.RuntimeErrorKind errorKind = AppError.RuntimeErrorKind.LOAD_FAILED;
            String msg = errorKind.format(runtime.runtimeId());
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.LOAD_TOTAL).addResult(ResultType.ERROR)
                    .addDurationMs(durationMs(loadStartedNs)).addErrorKind("RUNTIME").addErrorCode(errorKind.name())
                    .log("Runtime import I/O error");
            throw new AppException(new AppError.RuntimeError(errorKind, msg), msg, e);
        } finally {
            MdcContext.restoreOperation(previousOperation);
        }
    }


    /**
     * Prefix import: layers go into the runtime as they are downloaded, so the
     * import of the part already on disk overlaps the download of the rest. The
     * image only becomes visible in the runtime once every layer is in
     * ({@link IncrementalImageImport#finish()}).
     */
    private long importIncrementally(ManifestResult manifestResult, RuntimeAdapter runtime, ImageId imageId)
            throws IOException, InterruptedException {
        ImageReference image = new ImageReference(imageId.name(), imageId.tag());
        // The import starts when the first blob reaches the engine, not when the
        // whole image is on disk - the rest is still downloading at that point.
        AtomicLong handoverStartedNs = new AtomicLong(HANDOVER_NOT_STARTED);
        try (IncrementalImageImport session = runtime.beginIncrementalImport(image, manifestResult.manifest())) {
            archiveBuilder.streamLayers(imageId, manifestResult, new OciArchiveBuilder.LayerSink() {
                @Override
                public void onImageConfig(Path configBlob) throws IOException, InterruptedException {
                    markHandoverStart(handoverStartedNs);
                    session.imageConfig(configBlob);
                }

                @Override
                public void onLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
                    markHandoverStart(handoverStartedNs);
                    session.importLayer(layer, blobPath);
                }
            });
            session.finish();
        }
        long startedNs = handoverStartedNs.get();
        // Nothing was handed over: report a zero-length import rather than a
        // duration measured from somewhere the import never reached.
        return startedNs == HANDOVER_NOT_STARTED ? System.nanoTime() : startedNs;
    }

    private static void markHandoverStart(AtomicLong handoverStartedNs) {
        handoverStartedNs.compareAndSet(HANDOVER_NOT_STARTED, System.nanoTime());
    }

    public static ImageLoadingFacade createDefault(RegistryEndpoint endpoint, CacheAdapter cache, P2PExecutor p2p,
            Map<RuntimeId, RuntimeAdapter> runtimes, HostFilesystem fs) {
        return createDefault(endpoint, cache, p2p, runtimes, fs, null);
    }

    public static ImageLoadingFacade createDefault(RegistryEndpoint endpoint, CacheAdapter cache, P2PExecutor p2p,
            Map<RuntimeId, RuntimeAdapter> runtimes, HostFilesystem fs, MeterRegistry meterRegistry) {
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig);
        DispatcherLayerSourceMetrics layerMetrics = meterRegistry == null
                ? DispatcherLayerSourceMetrics.NOOP
                : new MicrometerDispatcherLayerSourceMetrics(meterRegistry);
        RequestDispatcher dispatcher = new SimpleRequestDispatcher(client, cache, p2p, new DispatcherConfig(), fs,
                layerMetrics);
        RuntimeRegistry registry = new RuntimeRegistry(runtimes);
        return new ImageLoadingFacade(dispatcher, registry, client, fs, null, null, null, p2p::close);
    }

    /**
     * Build ImageLoadingFacade from YAML config.
     */
    public static ImageLoadingFacade createFromConfig(Path configPath) throws Exception {
        return createFromConfig(configPath, null, null);
    }

    public static ImageLoadingFacade createFromConfig(Path configPath, Credentials credentialsOverride)
            throws Exception {
        return createFromConfig(configPath, credentialsOverride, null);
    }

    public static ImageLoadingFacade createFromConfig(Path configPath, Credentials credentialsOverride,
            MeterRegistry meterRegistry) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        GlobalConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();
        if (credentialsOverride != null) {
            endpoint = new RegistryEndpoint(endpoint.scheme(), endpoint.host(), endpoint.port(), credentialsOverride);
        }
        HostFilesystem fs = new NioHostFilesystem();
        AppConfig appConfig = config.app();
        AppConfig.DaemonConfig daemonConfig = appConfig != null ? appConfig.daemonOrDefault() : null;
        long maxCacheBytes = daemonConfig != null ? daemonConfig.maxCacheBytesOrDefault() : -1L;
        TempFileCacheAdapter cache = new TempFileCacheAdapter(fs, maxCacheBytes);
        HttpClientConfig httpConfig = new HttpClientConfig();
        AuthConfig authConfig = config.client() != null && config.client().auth() != null
                ? config.client().auth()
                : new AuthConfig();
        BlobPartialDownloadConfig blobPartialDownloadConfig = config.client() != null
                ? config.client().partialDownloadingOrDefault()
                : new BlobPartialDownloadConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, authConfig, blobPartialDownloadConfig,
                config.client().platformOrHostDefault());

        Map<RuntimeId, RuntimeAdapter> runtimes = new HashMap<>();
        RuntimeConfig runtimeConfig = config.runtime();
        boolean prefixImport = runtimeConfig == null
                ? RuntimeConfig.DEFAULT_PREFIX_IMPORT
                : runtimeConfig.prefixImportOrDefault();
        registerRuntime(runtimes, new PodmanRuntimeAdapter(prefixImport));
        registerRuntime(runtimes, new PortoRuntimeAdapter());
        registerRuntime(runtimes, new ContainerdRuntimeAdapter(prefixImport));
        String dockerCmd = runtimeConfig != null
                ? runtimeConfig.dockerCmdOrDefault()
                : RuntimeConfig.DEFAULT_DOCKER_BIN;
        registerRuntime(runtimes, new DockerRuntimeAdapter(fs, null, dockerCmd));

        if (runtimeConfig != null) {
            BoundedCommandExecution.setDefaultOutputConfig(runtimeConfig.outputConfigOrDefault());
            if (runtimeConfig.maxTasksCommandExecutor() != null) {
                BoundedCommandExecution.setMaxTasksCommandExecutor(runtimeConfig.maxTasksCommandExecutor());
            }
        }
        Path tempDir = appConfig != null ? appConfig.tempDirectoryPath() : null;
        List<String> allowedRegistries = appConfig != null ? appConfig.allowedRegistriesOrEmpty() : List.of();
        P2PExecutor p2p = new P2PExecutor.NoOp();
        ChallengeTokenAuthProvider challengeTokenAuthProvider = null;
        if (config.p2p() != null && config.p2p().dragonfly() != null && config.p2p().dragonfly().enabledOrDefault()) {
            challengeTokenAuthProvider = new ChallengeTokenAuthProvider(httpConfig, authConfig);
            p2p = new DragonflyGrpcP2PExecutor(endpoint, config.p2p().dragonfly(), challengeTokenAuthProvider);
        }
        DispatcherLayerSourceMetrics layerMetrics = meterRegistry == null
                ? DispatcherLayerSourceMetrics.NOOP
                : new MicrometerDispatcherLayerSourceMetrics(meterRegistry);
        ChallengeTokenAuthProvider finalChallengeTokenAuthProvider = challengeTokenAuthProvider;
        return new ImageLoadingFacade(
                new SimpleRequestDispatcher(client, cache, p2p, config.dispatcher(), fs, layerMetrics),
                new RuntimeRegistry(runtimes), client, fs, tempDir, allowedRegistries, () -> {
                    Exception error = null;
                    try {
                        cache.close();
                    } catch (Exception e) {
                        error = e;
                    }
                    if (finalChallengeTokenAuthProvider != null) {
                        try {
                            finalChallengeTokenAuthProvider.close();
                        } catch (Exception e) {
                            if (error == null) {
                                error = e;
                            } else {
                                error.addSuppressed(e);
                            }
                        }
                    }
                    if (error != null) {
                        throw error;
                    }
                }, p2p::close);
    }

    private void ensureRegistryAllowed(String registry) {
        if (allowedRegistries.isEmpty()) {
            return;
        }
        if (!allowedRegistries.contains(registry)) {
            String msg = AppError.RuntimeErrorKind.REGISTRY_NOT_ALLOWED.format(registry);
            throw new AppException(new AppError.RuntimeError(AppError.RuntimeErrorKind.REGISTRY_NOT_ALLOWED, msg), msg);
        }
    }

    @Override
    public void close() throws IOException {
        IOException error = null;
        error = closeResource(p2pCleaner, "Failed to close p2p executor", error);
        error = closeResource(cacheCleaner, "Failed to close cache adapter", error);
        error = closeResource(client::close, "Failed to close registry client", error);
        if (error != null) {
            throw error;
        }
    }

    /**
     * Default runtime adapters used by CLI and tests.
     */
    public static Map<RuntimeId, RuntimeAdapter> defaultRuntimes() {
        Map<RuntimeId, RuntimeAdapter> runtimes = new HashMap<>();
        registerRuntime(runtimes, new PodmanRuntimeAdapter());
        registerRuntime(runtimes, new PortoRuntimeAdapter());
        registerRuntime(runtimes, new DockerRuntimeAdapter());
        registerRuntime(runtimes, new ContainerdRuntimeAdapter());
        return Map.copyOf(runtimes);
    }

    private static void registerRuntime(Map<RuntimeId, RuntimeAdapter> runtimes, RuntimeAdapter adapter) {
        runtimes.put(adapter.runtimeId(), adapter);
    }

    @FunctionalInterface
    public interface CacheCleaner {
        void close() throws Exception;
    }

    private static IOException closeResource(CacheCleaner closer, String errorMessage, IOException previousError) {
        try {
            closer.close();
            return previousError;
        } catch (Exception e) {
            IOException closeError = new IOException(errorMessage, e);
            if (previousError == null) {
                return closeError;
            }
            previousError.addSuppressed(closeError);
            return previousError;
        }
    }

    private static long durationMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

}
