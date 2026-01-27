package riid.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
import riid.config.AppConfig;
import riid.config.ConfigLoader;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.p2p.P2PExecutor;
import riid.runtime.PodmanRuntimeAdapter;
import riid.runtime.PortoRuntimeAdapter;
import riid.runtime.RuntimeAdapter;

/**
 * Application entrypoint/facade: load image (dispatcher -> OCI -> runtime), optionally run.
 * Not a god-class: it wires existing components and delegates real work to them.
 */
public final class ImageLoadingFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadingFacade.class);

    private final OciArchiveBuilder archiveBuilder;
    private final RuntimeRegistry runtimeRegistry;
    private final RegistryClient client;
    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs) {
        this(dispatcher, runtimeRegistry, client, fs, null);
    }

    public ImageLoadingFacade(RequestDispatcher dispatcher,
                              RuntimeRegistry runtimeRegistry,
                              RegistryClient client,
                              HostFilesystem fs,
                              Path tempRoot) {
        this.archiveBuilder = new OciArchiveBuilder(dispatcher, fs, tempRoot);
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * High-level load: download/validate, assemble OCI, import into runtime.
     *
     * @return resolved ImageId used for runtime
     */
    public ImageId load(ImageId imageId, String runtimeId) {
        Objects.requireNonNull(imageId, "imageId");
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
        return new ImageLoadingFacade(dispatcher, registry, client, fs);
    }

    /**
     * Build ImageLoadFacade from YAML config.
     */
    public static ImageLoadingFacade createFromConfig(Path configPath) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        AppConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();
        TempFileCacheAdapter cache = new TempFileCacheAdapter();
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, cache);

        Map<String, RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());

        Path tempDir = config.app() != null ? config.app().tempDirPath() : null;
        HostFilesystem fs = new NioHostFilesystem();
        if (config.app() != null) {
            int threads = config.app().streamThreadsOrDefault();
            riid.runtime.PodmanRuntimeAdapter.setStreamThreads(threads);
            riid.runtime.PortoRuntimeAdapter.setStreamThreads(threads);
        }
        return new ImageLoadingFacade(
                new SimpleRequestDispatcher(client, cache, new P2PExecutor.NoOp(), fs),
                new RuntimeRegistry(runtimes),
                client,
                fs,
                tempDir);
    }

}


