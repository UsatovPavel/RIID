package riid.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.core.fs.HostFilesystem;
import riid.core.fs.PathSupport;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheEntry;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.ValidationException;
import riid.client.core.config.RegistryEndpoint;
import riid.core.model.manifest.RegistryApi;
import riid.core.hash.Sha256Utils;
import riid.p2p.config.DragonflyConfig;

/**
 * P2P executor backed by Dragonfly dfget CLI.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "CacheAdapter is a shared collaborator, not exposed mutably")
public final class DragonflyP2PExecutor implements P2PExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DragonflyP2PExecutor.class);

    private final RegistryEndpoint endpoint;
    private final CacheAdapter cache;
    private final HostFilesystem fs;
    private final DragonflyConfig config;
    private final DragonflyClientAdapter clientAdapter;
    private final DfcacheAdapter dfcacheAdapter;

    public DragonflyP2PExecutor(RegistryEndpoint endpoint,
                                CacheAdapter cache,
                                HostFilesystem fs,
                                DragonflyConfig config) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.cache = cache;
        this.fs = Objects.requireNonNull(fs, "fs");
        this.config = Objects.requireNonNull(config, "config");
        this.clientAdapter = new DragonflyClientAdapter(config);
        this.dfcacheAdapter = new DfcacheAdapter(config);
    }

    @Override
    public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType)
            throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        if (!config.enabledOrDefault()) {
            return Optional.empty();
        }
        Path tempPath = createTempFile();
        String url = endpoint.uri(RegistryApi.blobPath(repository, digest.toString())).toString();
        String taskId = dfcacheAdapter.taskIdForDigest(digest.toString());
        try {
            if (dfcacheAdapter.tryExportPersistentCache(taskId, tempPath)) {
                LOGGER.info("P2P persistent cache hit for layer {} (task_id={})", digest, taskId);
                return Optional.of(cacheOrTemp(tempPath, digest, mediaType, tempPath.toFile().length()));
            }
            LOGGER.info("P2P dfget start: url={}, target={}", url, tempPath);
            clientAdapter.download(url, tempPath, digest.toString());
            if (dfcacheAdapter.importIntoPersistentCache(taskId, tempPath)) {
                LOGGER.info("P2P persistent cache import success for layer {} (task_id={})", digest, taskId);
            }
            long actualSize = tempPath.toFile().length();
            if (size > 0 && actualSize > 0 && actualSize != size) {
                throw new IOException(
                        "P2P size mismatch for " + digest + ": expected " + size + ", got " + actualSize);
            }
            String computed = computeSha256(tempPath);
            if (!computed.equals(digest.toString())) {
                LOGGER.warn("P2P digest mismatch for {}: got {} (size={})",
                        digest, computed, actualSize);
                throw new IOException(
                        "P2P digest mismatch for " + digest + ": got " + computed
                                + ". size=" + actualSize);
            }
            return Optional.of(cacheOrTemp(tempPath, digest, mediaType, actualSize));
        } catch (DragonflyClientException e) {
            throw new IOException("P2P dfget failed for " + digest + ": " + e.kind(), e);
        }
    }

    @Override
    public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
        // Not supported by dfget CLI; leaving no-op for now.
    }

    private Path createTempFile() throws IOException {
        Path path = PathSupport.temporaryPath("p2p-", ".bin");
        return path;
    }

    private Path cacheOrTemp(Path tempPath, ImageDigest digest, CacheMediaType mediaType, long size) {
        if (cache == null) {
            return tempPath;
        }
        try {
            CacheEntry entry = cache.put(digest,
                    FilesystemCachePayload.of(fs, tempPath, size),
                    mediaType);
            Path resolved = cache.resolve(entry.key()).orElse(tempPath);
            if (!resolved.equals(tempPath)) {
                try {
                    fs.deleteIfExists(tempPath);
                } catch (IOException ex) {
                    LOGGER.warn("Failed to delete temp p2p file {}: {}", tempPath, ex.getMessage());
                }
            }
            return resolved;
        } catch (ValidationException ve) {
            LOGGER.warn("Validation error for cache put ({}): {}", mediaType, ve.getMessage());
        } catch (IllegalArgumentException iae) {
            LOGGER.warn("Unsupported media type for cache put ({}): {}", mediaType, iae.getMessage());
        } catch (IOException ex) {
            LOGGER.warn("Failed to put layer {} to cache: {}", digest, ex.getMessage());
        }
        return tempPath;
    }

    private String computeSha256(Path path) throws IOException {
        try (InputStream input = fs.newInputStream(path)) {
            return Sha256Utils.digest(input);
        } catch (IllegalStateException e) {
            throw new IOException("SHA-256 digest not available", e);
        }
    }
}
