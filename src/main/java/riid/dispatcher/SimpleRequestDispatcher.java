package riid.dispatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.core.model.manifest.MediaType;
import riid.dispatcher.logging.DispatcherLogErrorCode;
import riid.dispatcher.logging.DispatcherStructuredEvents;
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
        long cacheStart = System.nanoTime();
        Path cachedPath = null;
        if (cache != null && cache.has(digest)) {
            cachedPath = cache.get(digest)
                    .flatMap(entry -> cache.resolve(entry.key()))
                    .orElse(null);
        }
        if (cachedPath != null) {
            DispatcherStructuredEvents.sourceSelected(LOGGER, "cache", null, repository, digest);
            DispatcherStructuredEvents.sourceFetchSuccess(LOGGER, "cache", elapsedMs(cacheStart), repository, digest);
            return new FetchResult(digest, mediaType, cachedPath);
        }

        // 2) P2P
        if (p2p != null) {
            DispatcherStructuredEvents.sourceSelected(LOGGER, "p2p", "cache_miss", repository, digest);
            long p2pStarted = System.nanoTime();
            try {
                var p2pPath = p2p.fetch(
                        repository.value(),
                        digest,
                        sizeBytes,
                        CacheMediaType.from(mediaType.value()));
                if (p2pPath.isPresent()) {
                    DispatcherStructuredEvents.sourceFetchSuccess(
                            LOGGER, "p2p", elapsedMs(p2pStarted), repository, digest);
                    Path resultPath = p2pPath.get();
                    if (cache != null) {
                        try {
                            long size = sizeBytes > 0 ? sizeBytes : fs.size(resultPath);
                            CacheEntry entry = cache.put(digest,
                                    FilesystemCachePayload.of(fs, resultPath, size),
                                    CacheMediaType.from(mediaType.value()));
                            Path resolvedPath = cache.resolve(entry.key()).orElse(null);
                            if (resolvedPath != null) {
                                resultPath = resolvedPath;
                                try {
                                    fs.deleteIfExists(p2pPath.get());
                                } catch (Exception ex) {
                                    LOGGER.warn("Failed to delete temp p2p file {}: {}", p2pPath.get(), ex.getMessage());
                                }
                            }
                        } catch (ValidationException ve) {
                            LOGGER.warn("Validation error for cache put ({}): {}", mediaType, ve.getMessage());
                        } catch (IllegalArgumentException iae) {
                            LOGGER.warn("Unsupported media type for cache put ({}): {}", mediaType, iae.getMessage());
                        } catch (Exception ex) {
                            LOGGER.warn("Failed to put P2P layer {} to cache: {}", digest, ex.getMessage());
                        }
                    }
                    return new FetchResult(digest, mediaType, resultPath);
                }
                DispatcherStructuredEvents.sourceFetchMiss(
                        LOGGER,
                        "p2p",
                        elapsedMs(p2pStarted),
                        DispatcherLogErrorCode.P2P_MISS,
                        "not_found",
                        repository,
                        digest
                );
            } catch (IOException ex) {
                DispatcherStructuredEvents.sourceFetchError(
                        LOGGER,
                        "p2p",
                        elapsedMs(p2pStarted),
                        DispatcherLogErrorCode.P2P_FETCH_FAILED,
                        ex.getClass().getSimpleName(),
                        repository,
                        digest
                );
            }
        }

        // 3) Registry download
        DispatcherStructuredEvents.sourceSelected(
                LOGGER, "registry", "fallback_after_cache_p2p", repository, digest);
        long registryStarted = System.nanoTime();
        acquireRegistry();
        try {
            File tmp = createTemp();
            Path tempPath = tmp.toPath();
            BlobResult blob = client.fetchBlob(
                    new BlobRequest(
                            repository.value(),
                            digest.toString(),
                            sizeBytes,
                            mediaType.value(),
                            new BlobRequest.RangeSpec.All()),
                    tmp);
            DispatcherStructuredEvents.sourceFetchSuccess(
                    LOGGER, "registry", elapsedMs(registryStarted), repository, digest);

            Path resultPath = tempPath;
            boolean deleteTemp = false;
            if (cache != null) {
                try {
                    CacheEntry entry = cache.put(ImageDigest.parse(blob.digest()),
                            FilesystemCachePayload.of(fs, tempPath, tmp.length()),
                            CacheMediaType.from(blob.mediaType()));
                    Path resolvedPath = cache.resolve(entry.key()).orElse(null);
                    if (resolvedPath != null) {
                        resultPath = resolvedPath;
                        deleteTemp = true;
                    }
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
                            resultPath,
                            blob.size(),
                            CacheMediaType.from(blob.mediaType()));
                } catch (Exception ex) {
                    LOGGER.warn("P2P publish failed for {}: {}", blob.digest(), ex.getMessage());
                }
            }

            if (deleteTemp) {
                try {
                    fs.deleteIfExists(tempPath);
                } catch (Exception ex) {
                    LOGGER.warn("Failed to delete temp layer {}: {}", tempPath, ex.getMessage());
                }
            }

            return new FetchResult(ImageDigest.parse(blob.digest()),
                    MediaType.from(blob.mediaType()),
                    resultPath);
        } catch (RuntimeException ex) {
            DispatcherStructuredEvents.sourceFetchError(
                    LOGGER,
                    "registry",
                    elapsedMs(registryStarted),
                    DispatcherLogErrorCode.REGISTRY_FETCH_FAILED,
                    ex.getClass().getSimpleName(),
                    repository,
                    digest
            );
            throw ex;
        } finally {
            releaseRegistry();
        }
    }

    private File createTemp() {
        try {
            var path = PathSupport.temporaryPath("layer-", ".bin");
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

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}

