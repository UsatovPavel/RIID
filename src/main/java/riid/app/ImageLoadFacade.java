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
import riid.app.ociarchive.OciArchive;
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
public final class ImageLoadFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadFacade.class);

    private final OciArchiveBuilder archiveBuilder;
    private final RuntimeRegistry runtimeRegistry;
    private final RegistryClient client;
    private final String defaultRegistry;

    public ImageLoadFacade(RequestDispatcher dispatcher, RuntimeRegistry runtimeRegistry, RegistryClient client) {
        this(dispatcher, runtimeRegistry, client, new NioHostFilesystem(null), null);
    }

    public ImageLoadFacade(RequestDispatcher dispatcher,
                           RuntimeRegistry runtimeRegistry,
                           RegistryClient client,
                           HostFilesystem fs,
                           String defaultRegistry) {
        this.archiveBuilder = new OciArchiveBuilder(dispatcher, fs);
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.client = Objects.requireNonNull(client, "client");
        this.defaultRegistry = defaultRegistry;
    }

    /**
     * High-level load: download/validate, assemble OCI, import into runtime.
     *
     * @return refName used for runtime
     */
    public String load(String repository, String reference, String runtimeId) {
        if (defaultRegistry == null || defaultRegistry.isBlank()) {
            throw new IllegalStateException("Default registry is not configured");
        }
        ImageId imageId = ImageId.fromRegistry(defaultRegistry, repository, reference);
        return load(imageId, runtimeId);
    }

    /**
     * Load using a prepared image id and runtime id; client/runtime lookup happens here.
     */
    public String load(ImageId imageId, String runtimeId) {
        Objects.requireNonNull(imageId, "imageId");
        ManifestResult manifestResult = client.fetchManifest(imageId.name(), imageId.reference());
        RuntimeAdapter runtime = runtimeRegistry.get(runtimeId);
        ImageId resolved = imageId.withDigest(manifestResult.digest());
        return load(manifestResult, runtime, resolved);
    }

    /**
     * Load using prepared manifest result and runtime.
     *
     * @return refName used for runtime
     */
    public String load(ManifestResult manifestResult, RuntimeAdapter runtime, ImageId imageId) {
        Objects.requireNonNull(manifestResult, "manifestResult");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(imageId, "imageId");
        try (OciArchive archive = archiveBuilder.build(imageId, manifestResult)) {
            runtime.importImage(archive.archivePath());
            LOGGER.info("Loaded {} into runtime {} at {}", imageId, runtime.runtimeId(), archive.archivePath());
            return imageId.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(
                    new AppError.Runtime(AppError.RuntimeKind.LOAD_FAILED,
                            "Failed to load image into runtime " + runtime.runtimeId()),
                    "Failed to load image into runtime " + runtime.runtimeId(), e);
        } catch (IOException e) {
            throw new AppException(
                    new AppError.Runtime(AppError.RuntimeKind.LOAD_FAILED,
                            "Failed to load image into runtime " + runtime.runtimeId()),
                    "Failed to load image into runtime " + runtime.runtimeId(), e);
        }
    }

    public static ImageLoadFacade createDefault(RegistryEndpoint endpoint,
                                                CacheAdapter cache,
                                                P2PExecutor p2p,
                                                Map<String, RuntimeAdapter> runtimes,
                                                HostFilesystem fs) {
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, cache);
        RequestDispatcher dispatcher = new SimpleRequestDispatcher(client, cache, p2p);
        RuntimeRegistry registry = new RuntimeRegistry(runtimes);
        String registryName = ImageId.registryFor(endpoint);
        return new ImageLoadFacade(dispatcher, registry, client, fs, registryName);
    }

    /**
     * Build ImageLoadFacade from YAML config.
     */
    public static ImageLoadFacade createFromConfig(Path configPath) throws Exception {
        LOGGER.info("Loading config from {}", configPath.toAbsolutePath());
        AppConfig config = ConfigLoader.load(configPath);

        RegistryEndpoint endpoint = config.client().registries().getFirst();
        TempFileCacheAdapter cache = new TempFileCacheAdapter();

        Map<String, RuntimeAdapter> runtimes = new HashMap<>();
        runtimes.put("podman", new PodmanRuntimeAdapter());
        runtimes.put("porto", new PortoRuntimeAdapter());

        Path tempDir = config.app() != null ? config.app().tempDirPath() : null;
        HostFilesystem fs = new NioHostFilesystem(tempDir);

        return createDefault(endpoint, cache, new P2PExecutor.NoOp(), runtimes, fs);
    }

}


