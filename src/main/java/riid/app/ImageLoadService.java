package riid.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.CacheAdapter;
import riid.cache.ImageDigest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.Manifest;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.DispatcherRuntimeIntegrator;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.p2p.P2PExecutor;
import riid.runtime.RuntimeAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Application entrypoint/facade: load image (dispatcher -> OCI -> runtime), optionally run.
 * Not a god-class: it wires existing components and delegates real work to them.
 */
public final class ImageLoadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadService.class);

    private final RequestDispatcher dispatcher;
    private final DispatcherRuntimeIntegrator integrator;
    private final RuntimeRegistry runtimeRegistry;
    private final RegistryClient client;

    public ImageLoadService(RequestDispatcher dispatcher, RuntimeRegistry runtimeRegistry, RegistryClient client) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.integrator = new DispatcherRuntimeIntegrator(dispatcher);
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * High-level load: download/validate, assemble OCI, import into runtime.
     *
     * @return refName used for runtime
     */
    public String load(String repository, String reference, String runtimeId) {
        try {
            // Fetch manifest via client
            ManifestResult manifestResult = client.fetchManifest(repository, reference);
            // Assemble OCI archive using dispatcher to obtain layers (cache/P2P/registry)
            Path archive = buildOciArchive(repository, reference, manifestResult);
            RuntimeAdapter runtime = runtimeRegistry.get(runtimeId);
            runtime.importImage(archive);
            LOGGER.info("Loaded {}@{} into runtime {} at {}", repository, reference, runtimeId, archive);
            return runtimeRef(repository, reference);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UncheckedAppException("Failed to load image into runtime " + runtimeId, e);
        }
    }

    private String runtimeRef(String repository, String reference) {
        // normalize to docker.io/<repo>:<ref>
        if (repository.startsWith("docker.io/")) {
            return repository + ":" + reference;
        }
        return "docker.io/" + repository + ":" + reference;
    }

    /**
     * Assemble OCI archive from manifest + blobs fetched via RegistryClient.
     * Uses cache if provided to store blobs.
     */
    private Path buildOciArchive(String repository, String reference, ManifestResult manifestResult) throws IOException, InterruptedException {
        Manifest manifest = manifestResult.manifest();
        Path ociDir = Files.createTempDirectory("oci-layout");
        Path blobsDir = ociDir.resolve("blobs").resolve("sha256");
        Files.createDirectories(blobsDir);

        // Config
        var cfg = manifest.config();
        pullLayer(repository, cfg.digest(), cfg.size(), cfg.mediaType(), blobsDir);

        // Layers
        for (var layer : manifest.layers()) {
            pullLayer(repository, layer.digest(), layer.size(), layer.mediaType(), blobsDir);
        }

        // Manifest blob
        byte[] manifestBytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(manifest);
        String manifestDigest = manifestResult.digest().replace("sha256:", "");
        Files.write(blobsDir.resolve(manifestDigest), manifestBytes);

        // oci-layout
        Files.writeString(ociDir.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");

        // index.json with ref name
        String refName = runtimeRef(repository, reference);
        String index = """
                {
                  "schemaVersion": 2,
                  "manifests": [
                    {
                      "mediaType": "application/vnd.oci.image.manifest.v1+json",
                      "size": %d,
                      "digest": "sha256:%s",
                      "annotations": {
                        "org.opencontainers.image.ref.name": "%s"
                      }
                    }
                  ]
                }
                """.formatted(manifestBytes.length, manifestResult.digest().replace("sha256:", ""), refName);
        Files.writeString(ociDir.resolve("index.json"), index);

        Path archive = Files.createTempFile("oci-archive", ".tar");
        runTar(archive, ociDir);
        return archive;
    }

    private void pullLayer(String repository,
                           String digest,
                           long size,
                           String mediaType,
                           Path blobsDir) throws IOException {
        // use dispatcher to honor cache/P2P
        var fetched = dispatcher.fetchLayer(repository, digest, size, mediaType);
        File tmp = new File(fetched.path());
        BlobResult blob = new BlobResult(fetched.digest(), tmp.length(), fetched.mediaType(), fetched.path());
        ImageDigest imgDigest = ImageDigest.parse(blob.digest());
        Files.copy(tmp.toPath(), blobsDir.resolve(imgDigest.hex()));
    }

    private static void runTar(Path archive, Path ociDir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-cf", archive.toString(), "-C", ociDir.toString(), ".")
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar failed with exit " + code);
        }
    }

    /**
     * Default wiring for CLI/embedders: build dispatcher, runtime registry, and return App.
     */
    public static ImageLoadService createDefault(RegistryEndpoint endpoint,
                                                 CacheAdapter cache,
                                                 P2PExecutor p2p,
                                                 Map<String, RuntimeAdapter> runtimes) {
        HttpClientConfig httpConfig = new HttpClientConfig();
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, cache);
        RequestDispatcher dispatcher = new SimpleRequestDispatcher(client, cache, p2p);
        RuntimeRegistry registry = new RuntimeRegistry(runtimes);
        return new ImageLoadService(dispatcher, registry, client);
    }

}


