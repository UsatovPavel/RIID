package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.HostFilesystem;
import riid.core.fs.PathSupport;
import riid.core.model.manifest.RegistryApi;

/**
 * P2P executor via gRPC to dfdaemon (v2 API). Fetch-only: returns path to downloaded file.
 * Dispatcher is responsible for cache.put().
 * <p>
 * Uses dfdaemonAddr from config (unix socket or tcp).
 * For unix socket (e.g. unix:///var/run/dragonfly/dfdaemon.sock), output goes to parent/output
 * (hostPath mount) so dfdaemon can write and RIID can read on host.
 */
public final class DragonflyGrpcP2PExecutor implements P2PExecutor {
    private final RegistryEndpoint endpoint;
    private final HostFilesystem fs;
    private final DragonflyConfig config;
    private final DfdaemonDownloaderFactory downloaderFactory;

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint,
                                    HostFilesystem fs,
                                    DragonflyConfig config) {
        this(endpoint, fs, config, DfdaemonDownloadClient::new);
    }

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint,
                                    HostFilesystem fs,
                                    DragonflyConfig config,
                                    DfdaemonDownloaderFactory downloaderFactory) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.fs = Objects.requireNonNull(fs, "fs");
        this.config = Objects.requireNonNull(config, "config");
        this.downloaderFactory = Objects.requireNonNull(downloaderFactory, "downloaderFactory");
    }

    @Override
    public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType)
            throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        if (!config.enabledOrDefault()) {
            return Optional.empty();
        }
        String url = endpoint.uri(RegistryApi.blobPath(repository, digest.toString())).toString();
        String dfdaemonAddr = config.dfdaemonAddr();
        Path outputPath;
        String outputForRequest;
        Path hostOutputDir = unixSocketHostOutputDir(dfdaemonAddr);
        if (hostOutputDir != null) {
            String filename = "p2p-" + UUID.randomUUID() + ".bin";
            outputPath = hostOutputDir.resolve(filename);
            outputForRequest = hostOutputDir.resolve(filename).toString();
        } else {
            outputPath = PathSupport.temporaryPath("p2p-", ".bin");
            outputForRequest = outputPath.toAbsolutePath().toString();
        }
        var request = DownloadTaskRequestBuilder.build(url, outputForRequest,
                digest.toString(), Collections.emptyMap());
        try (DfdaemonDownloader client = downloaderFactory.create(dfdaemonAddr)) {
            Path result = client.download(request, outputPath);
            return Optional.of(result);
        }
    }

    /**
     * For unix socket (e.g. unix:///var/run/dragonfly/dfdaemon.sock), returns host output dir
     * (parent of socket + /output). dfdaemon writes there via hostPath mount.
     * For TCP proxy (e.g. 127.0.0.1:50051 via socat), use DFDAEMON_OUTPUT_DIR if set.
     */
    private static Path unixSocketHostOutputDir(String addr) {
        if (addr != null && addr.trim().startsWith("unix://")) {
            String path = addr.trim().substring(7).trim();
            if (!path.isEmpty()) {
                Path p = Path.of(path);
                Path parent = p.getParent();
                if (parent != null) {
                    return parent.resolve("output");
                }
            }
        }
        String envDir = System.getenv("DFDAEMON_OUTPUT_DIR");
        return (envDir != null && !envDir.isBlank()) ? Path.of(envDir.trim()) : null;
    }

    @Override
    public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
        // Not supported
    }
}
