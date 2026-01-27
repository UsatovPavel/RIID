package riid.dispatcher;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.app.fs.HostFilesystem;
import riid.app.fs.PathSupport;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.ValidationException;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.core.model.manifest.MediaType;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.dispatcher.model.RepositoryName;
import riid.p2p.P2PExecutor;

/**
 * Simple dispatcher: cache -> P2P -> registry (registry concurrency limit is configurable).
 */
@SuppressFBWarnings({"EI_EXPOSE_REP2"})
public class SimpleRequestDispatcher implements RequestDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleRequestDispatcher.class);

    private final RegistryClient client;
    private final CacheAdapter cache;
    private final P2PExecutor p2p;
    private final HostFilesystem fs;
    private final Optional<Semaphore> registryLimiter; // limits concurrent downloads from registry

    public SimpleRequestDispatcher(RegistryClient client,
                                   CacheAdapter cache,
                                   P2PExecutor p2p,
                                   HostFilesystem fs) {
        this(client, cache, p2p, new DispatcherConfig(), fs);
    }

    public SimpleRequestDispatcher(RegistryClient client,
                                   CacheAdapter cache,
                                   P2PExecutor p2p,
                                   DispatcherConfig config,
                                   HostFilesystem fs) {
        this.client = Objects.requireNonNull(client);
        this.cache = cache;
        this.p2p = p2p;
        this.fs = Objects.requireNonNull(fs, "fs");
        int maxConc = config != null ? config.maxConcurrentRegistry() : 0;
        this.registryLimiter = maxConc > 0 ? Optional.of(new Semaphore(maxConc)) : Optional.empty();
    }

    @Override
    public FetchResult fetchImage(ImageRef ref) {
        String reference = ref.digest() != null && !ref.digest().isBlank() ? ref.digest() : ref.tag();
        ManifestResult manifest = client.fetchManifest(ref.repository(), reference);
        var layer = manifest.manifest().layers().getFirst();
        return fetchLayer(new RepositoryName(ref.repository()),
                ImageDigest.parse(layer.digest()),
                layer.size(),
                MediaType.from(layer.mediaType()));
    }

    @Override
    public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes, MediaType mediaType) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest);

        // 1) cache
        Path cachedPath = null;
        if (cache != null && cache.has(digest)) {
            cachedPath = cache.get(digest)
                    .flatMap(entry -> cache.resolve(entry.key()))
                    .orElse(null);
        }
        if (cachedPath != null) {
            LOGGER.info("cache hit for layer {}", digest);
            return new FetchResult(digest, mediaType, cachedPath);
        }

        // 2) P2P
        if (p2p != null) {
            try {
                var p2pPath = p2p.fetch(digest, sizeBytes, CacheMediaType.from(mediaType.value()));
                if (p2pPath.isPresent()) {
                    LOGGER.info("p2p hit for layer {}", digest);
                    return new FetchResult(digest, mediaType, p2pPath.get());
                }
            } catch (Exception ex) {
                LOGGER.warn("P2P fetch failed for layer {}: {}", digest, ex.getMessage());
            }
        }

        // 3) Registry download
        acquireRegistry();
        try {
            File tmp = createTemp();
            BlobResult blob = client.fetchBlob(
                    new BlobRequest(repository.value(), digest.toString(), sizeBytes, mediaType.value()),
                    tmp);
            LOGGER.info("downloaded layer {} from registry", digest);

            if (cache != null) {
                try {
                    cache.put(ImageDigest.parse(blob.digest()),
                            FilesystemCachePayload.of(tmp.toPath(), tmp.length()),
                            CacheMediaType.from(blob.mediaType()));
                } catch (ValidationException ve) {
                    LOGGER.warn("Validation error for cache put ({}): {}", blob.mediaType(), ve.getMessage());
                } catch (IllegalArgumentException iae) {
                    LOGGER.warn("Unsupported media type for cache put ({}): {}", blob.mediaType(), iae.getMessage());
                } catch (Exception ex) {
                    LOGGER.warn("Failed to put layer {} to cache: {}", blob.digest(), ex.getMessage());
                }
            }
            if (p2p != null) {
                try {
                    p2p.publish(
                            ImageDigest.parse(blob.digest()),
                            Path.of(blob.path()),
                            blob.size(),
                            CacheMediaType.from(blob.mediaType()));
                } catch (Exception ex) {
                    LOGGER.warn("P2P publish failed for {}: {}", blob.digest(), ex.getMessage());
                }
            }

            return new FetchResult(ImageDigest.parse(blob.digest()),
                    MediaType.from(blob.mediaType()),
                    Path.of(blob.path()));
        } finally {
            releaseRegistry();
        }
    }

    private File createTemp() {
        try {
            var path = PathSupport.tempPath("layer-", ".bin");
            fs.createFile(path);
            return path.toFile();
        } catch (Exception e) {
            throw new RuntimeException("Cannot create temp file", e);
        }
    }

    private void acquireRegistry() {
        registryLimiter.ifPresent(limiter -> {
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for registry slot", e);
            }
        });
    }

    private void releaseRegistry() {
        registryLimiter.ifPresent(Semaphore::release);
    }
}

