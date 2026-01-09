package riid.dispatcher;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.CacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.p2p.P2PExecutor;

import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Simple dispatcher: cache -> P2P -> registry (registry concurrency limit is configurable).
 */
@SuppressFBWarnings({"EI_EXPOSE_REP2"})
public class SimpleRequestDispatcher implements RequestDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleRequestDispatcher.class);

    private final RegistryClient client;
    private final CacheAdapter cache;
    private final P2PExecutor p2p;
    private final Optional<Semaphore> registryLimiter; // limits concurrent downloads from registry

    public SimpleRequestDispatcher(RegistryClient client, CacheAdapter cache, P2PExecutor p2p) {
        this(client, cache, p2p, new DispatcherConfig());
    }

    public SimpleRequestDispatcher(RegistryClient client,
                                   CacheAdapter cache,
                                   P2PExecutor p2p,
                                   DispatcherConfig config) {
        this.client = Objects.requireNonNull(client);
        this.cache = cache;
        this.p2p = p2p;
        int maxConc = config != null ? config.maxConcurrentRegistry() : 0;
        this.registryLimiter = maxConc > 0 ? Optional.of(new Semaphore(maxConc)) : Optional.empty();
    }

    @Override
    public FetchResult fetchImage(ImageRef ref) {
        // 1) Manifest from registry
        ManifestResult manifest = client.fetchManifest(ref.repository(), ref.reference());

        // 2) Try cache for each layer
        var layer = manifest.manifest().layers().getFirst();
        var digest = riid.cache.ImageDigest.parse(layer.digest());
        String cachedPath = (cache != null && cache.has(digest))
                ? cache.get(digest).map(riid.cache.CacheEntry::locator).orElse(null)
                : null;
        if (cachedPath != null) {
            LOGGER.info("cache hit for layer {}", layer.digest());
            return new FetchResult(layer.digest(), layer.mediaType(), cachedPath);
        }

        // 3) Try P2P (if wired)
        if (p2p != null) {
            var p2pPath = p2p.fetch(layer.digest(), layer.size(), layer.mediaType());
            if (p2pPath.isPresent()) {
                LOGGER.info("p2p hit for layer {}", layer.digest());
                return new FetchResult(layer.digest(), layer.mediaType(), p2pPath.get());
            }
        }

        // 4) Registry download (with limiter if set)
        acquireRegistry();
        try {
            File tmp = createTemp();
            BlobResult blob = client.fetchBlob(
                    new BlobRequest(ref.repository(), layer.digest(), layer.size(), layer.mediaType()),
                    tmp);
            LOGGER.info("downloaded layer {} from registry", layer.digest());

            // 5) Publish to P2P/cache
            if (cache != null) {
                try {
                    cache.put(riid.cache.ImageDigest.parse(blob.digest()),
                            riid.cache.CachePayload.of(tmp.toPath(), tmp.length()),
                            riid.cache.CacheMediaType.from(blob.mediaType()));
                } catch (Exception ex) {
                    LOGGER.warn("Failed to put layer {} to cache: {}", blob.digest(), ex.getMessage());
                }
            }
            if (p2p != null) {
                p2p.publish(blob.digest(), blob.path(), blob.size(), blob.mediaType());
            }

            return new FetchResult(blob.digest(), blob.mediaType(), blob.path());
        } finally {
            releaseRegistry();
        }
    }

    private File createTemp() {
        try {
            File f = File.createTempFile("layer-", ".bin");
            f.deleteOnExit();
            return f;
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

