package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.HostFilesystem;
import riid.core.fs.PathSupport;
import riid.core.model.manifest.RegistryApi;

/**
 * P2P executor via gRPC to dfdaemon. Fetch-only: returns path to downloaded file.
 * Dispatcher is responsible for cache.put().
 * <p>
 * Uses dfdaemonAddr from config (unix socket or tcp).
 */
public final class DragonflyGrpcP2PExecutor implements P2PExecutor {
    private final RegistryEndpoint endpoint;
    private final HostFilesystem fs;
    private final DragonflyConfig config;

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint,
                                    HostFilesystem fs,
                                    DragonflyConfig config) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.fs = Objects.requireNonNull(fs, "fs");
        this.config = Objects.requireNonNull(config, "config");
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
        Path outputPath = PathSupport.temporaryPath("p2p-", ".bin");
        var request = DownloadTaskRequestBuilder.build(url, outputPath.toAbsolutePath().toString(),
                digest.toString(), Collections.emptyMap());
        try (DfdaemonDownloadClient client = new DfdaemonDownloadClient(config.dfdaemonAddr())) {
            Path result = client.download(request, outputPath);
            return Optional.of(result);
        }
    }

    @Override
    public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
        // Not supported
    }
}
