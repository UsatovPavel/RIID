package riid.dispatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.core.fs.HostFilesystem;
import riid.core.fs.PathSupport;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheLease;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.ValidationException;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.core.model.manifest.MediaType;
import riid.dispatcher.core.config.DispatcherConfig;
import riid.dispatcher.core.logging.DispatcherMilestoneLogger;
import riid.dispatcher.metrics.DispatcherLayerSourceMetrics;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.dispatcher.model.RepositoryName;
import riid.p2p.P2PExecutor;

/**
 * Simple dispatcher: cache -> P2P -> registry (registry concurrency limit is
 * configurable).
 */
@SuppressFBWarnings({"EI_EXPOSE_REP2"})
public class SimpleRequestDispatcher implements RequestDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleRequestDispatcher.class);

    private final RegistryClient client;
    private final CacheAdapter cache;
    private final P2PExecutor p2p;
    private final HostFilesystem fs;
    private final Optional<Semaphore> registryLimiter; // limits concurrent downloads from registry
    private final DispatcherMilestoneLogger stepLogger;
    private final DispatcherLayerSourceMetrics layerSourceMetrics;

    public SimpleRequestDispatcher(RegistryClient client, CacheAdapter cache, P2PExecutor p2p, HostFilesystem fs) {
        this(client, cache, p2p, new DispatcherConfig(), fs, DispatcherLayerSourceMetrics.NOOP);
    }

    public SimpleRequestDispatcher(RegistryClient client, CacheAdapter cache, P2PExecutor p2p, DispatcherConfig config,
            HostFilesystem fs) {
        this(client, cache, p2p, config, fs, DispatcherLayerSourceMetrics.NOOP);
    }

    public SimpleRequestDispatcher(RegistryClient client, CacheAdapter cache, P2PExecutor p2p, DispatcherConfig config,
            HostFilesystem fs, DispatcherLayerSourceMetrics layerSourceMetrics) {
        this.client = Objects.requireNonNull(client);
        this.cache = cache;
        this.p2p = p2p;
        this.fs = Objects.requireNonNull(fs, "fs");
        int maxConc = config != null ? config.maxConcurrentRegistry() : 0;
        this.registryLimiter = maxConc > 0 ? Optional.of(new Semaphore(maxConc)) : Optional.empty();
        this.stepLogger = new DispatcherMilestoneLogger(LOGGER);
        this.layerSourceMetrics = Objects.requireNonNull(layerSourceMetrics, "layerSourceMetrics");
    }

    @Override
    public FetchResult fetchImage(ImageRef ref) {
        String reference = ref.digest() != null && !ref.digest().isBlank() ? ref.digest() : ref.tag();
        ManifestResult manifest = client.fetchManifest(ref.repository(), reference);
        var layer = manifest.manifest().layers().getFirst();
        return fetchLayer(new RepositoryName(ref.repository()), ImageDigest.parse(layer.digest()), layer.size(),
                MediaType.from(layer.mediaType()));
    }

    @Override
    public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes, MediaType mediaType) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest);
        long selectStartedNs = System.nanoTime();

        // 1) cache
        Optional<CacheLease> cached = cache == null ? Optional.empty() : cache.acquire(digest);
        if (cached.isPresent()) {
            CacheLease lease = cached.orElseThrow();
            Path cachedPath = lease.path();
            stepLogger.sourceSelectFromCache(selectStartedNs);
            long fetchStartedNs = System.nanoTime();
            LOGGER.info("cache hit for layer {}", digest);
            stepLogger.sourceFetchFromCache(fetchStartedNs);
            layerSourceMetrics.recordLayerFetch("cache");
            recordLayerBytes("cache", sizeBytes, cachedPath);
            return FetchResult.leased(digest, mediaType, lease);
        }

        // 2) P2P
        if (p2p != null) {
            long fetchStartedNs = System.nanoTime();
            try {
                var p2pPath = p2p.fetch(repository.value(), digest, sizeBytes, CacheMediaType.from(mediaType.value()));
                if (p2pPath.isPresent()) {
                    LOGGER.info("p2p hit for layer {}", digest);
                    Path resultPath = p2pPath.get();
                    CacheLease resultLease = null;
                    if (cache != null) {
                        try {
                            long size = sizeBytes > 0 ? sizeBytes : fs.size(resultPath);
                            resultLease = cache.put(digest, FilesystemCachePayload.of(fs, resultPath, size),
                                    CacheMediaType.from(mediaType.value()));
                            resultPath = resultLease.path();
                            try {
                                fs.deleteIfExists(p2pPath.get());
                            } catch (Exception ex) {
                                LOGGER.warn("Failed to delete temp p2p file {}: {}", p2pPath.get(), ex.getMessage());
                            }
                        } catch (ValidationException ve) {
                            LOGGER.warn("Validation error for cache put ({}): {}", mediaType, ve.getMessage());
                        } catch (IllegalArgumentException iae) {
                            LOGGER.warn("Unsupported media type for cache put ({}): {}", mediaType, iae.getMessage());
                        } catch (Exception ex) {
                            LOGGER.warn("Failed to put P2P layer {} to cache: {}", digest, ex.getMessage());
                        }
                    }
                    stepLogger.sourceSelectFromP2p(selectStartedNs);
                    stepLogger.sourceFetchFromP2p(fetchStartedNs);
                    layerSourceMetrics.recordLayerFetch("p2p");
                    recordLayerBytes("p2p", sizeBytes, resultPath);
                    return resultLease == null
                            ? new FetchResult(digest, mediaType, resultPath)
                            : FetchResult.leased(digest, mediaType, resultLease);
                }
            } catch (IOException ex) {
                LOGGER.warn("P2P fetch failed for layer {}: {}", digest, ex.getMessage());
            }
        }

        // 3) Registry download
        stepLogger.sourceSelectFromRegistry(selectStartedNs);
        long fetchStartedNs = System.nanoTime();
        boolean fetchSucceeded = false;
        acquireRegistry();
        try {
            File tmp = createTemp();
            Path tempPath = tmp.toPath();
            BlobResult blob = client.fetchBlob(new BlobRequest(repository.value(), digest.toString(), sizeBytes,
                    mediaType.value(), new BlobRequest.RangeSpec.All()), tmp);
            LOGGER.info("downloaded layer {} from registry", digest);

            Path resultPath = tempPath;
            boolean deleteTemp = false;
            CacheLease resultLease = null;
            if (cache != null) {
                try {
                    resultLease = cache.put(ImageDigest.parse(blob.digest()),
                            FilesystemCachePayload.of(fs, tempPath, tmp.length()),
                            CacheMediaType.from(blob.mediaType()));
                    resultPath = resultLease.path();
                    deleteTemp = true;
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
                    p2p.publish(ImageDigest.parse(blob.digest()), resultPath, blob.size(),
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

            fetchSucceeded = true;
            layerSourceMetrics.recordLayerFetch("registry");
            long registryBytes = blob.size() > 0 ? blob.size() : tmp.length();
            layerSourceMetrics.recordLayerFetchedBytes("registry", registryBytes);
            return resultLease == null
                    ? new FetchResult(ImageDigest.parse(blob.digest()), MediaType.from(blob.mediaType()), resultPath)
                    : FetchResult.leased(ImageDigest.parse(blob.digest()), MediaType.from(blob.mediaType()),
                            resultLease);
        } catch (RuntimeException e) {
            stepLogger.sourceFetchFailed(fetchStartedNs, e);
            throw e;
        } catch (Error e) {
            stepLogger.sourceFetchFailed(fetchStartedNs, e);
            throw e;
        } finally {
            if (fetchSucceeded) {
                stepLogger.sourceFetchFromRegistry(fetchStartedNs);
            }
            releaseRegistry();
        }
    }

    private void recordLayerBytes(String source, long declaredSize, Path path) {
        layerSourceMetrics.recordLayerFetchedBytes(source, layerPayloadBytes(declaredSize, path));
    }

    private long layerPayloadBytes(long declaredSize, Path path) {
        if (declaredSize > 0) {
            return declaredSize;
        }
        if (path == null) {
            return 0;
        }
        try {
            return fs.size(path);
        } catch (Exception e) {
            LOGGER.debug("Could not stat layer path {}: {}", path, e.getMessage());
            return 0;
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
}
