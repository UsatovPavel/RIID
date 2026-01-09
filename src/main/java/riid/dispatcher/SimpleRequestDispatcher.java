package riid.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.CacheAdapter;
import riid.client.RegistryClient;
import riid.client.blob.BlobRequest;
import riid.client.blob.BlobResult;
import riid.client.manifest.ManifestResult;
import riid.p2p.P2PExecutor;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Simple dispatcher: cache -> P2P -> registry (registry concurrency limit is configurable).
 */
public class SimpleRequestDispatcher implements RequestDispatcher {
    private static final Logger log = LoggerFactory.getLogger(SimpleRequestDispatcher.class);

    private final RegistryClient client;
    private final CacheAdapter cache;
    private final P2PExecutor p2p;
    private final Semaphore registryLimiter; // limits concurrent downloads from regisry-client

    public SimpleRequestDispatcher(RegistryClient client, CacheAdapter cache, P2PExecutor p2p) {
        this(client, cache, p2p, DispatcherConfig.builder().build());
    }

    public SimpleRequestDispatcher(RegistryClient client, CacheAdapter cache, P2PExecutor p2p, DispatcherConfig config) {
        this.client = Objects.requireNonNull(client);
        this.cache = cache;
        this.p2p = p2p;
        int maxConc = config != null ? config.maxConcurrentRegistry() : 0;
        this.registryLimiter = maxConc > 0 ? new Semaphore(maxConc) : null;
    }

    @Override
    public FetchResult fetchImage(ImageRef ref) {
        // 1) Manifest from registry
        ManifestResult manifest = client.fetchManifest(ref.repository(), ref.reference());

        // 2) Try cache for each layer
        var layer = manifest.manifest().layers().getFirst();
        String cachedPath = (cache != null && cache.has(layer.digest()))
                ? cache.getPath(layer.digest()).orElse(null)
                : null;
        if (cachedPath != null) {
            log.info("cache hit for layer {}", layer.digest());
            return new FetchResult(layer.digest(), layer.mediaType(), cachedPath);
        }

        // 3) Try P2P (if wired)
        if (p2p != null) {
            var p2pPath = p2p.fetch(layer.digest(), layer.size(), layer.mediaType());
            if (p2pPath.isPresent()) {
                log.info("p2p hit for layer {}", layer.digest());
                return new FetchResult(layer.digest(), layer.mediaType(), p2pPath.get());
            }
        }

        // 4) Registry download (with limiter if set)
        acquireRegistry();
        try {
            File tmp = createTemp();
            BlobResult blob = client.fetchBlob(new BlobRequest(ref.repository(), layer.digest(), layer.size(), layer.mediaType()), tmp);
            log.info("downloaded layer {} from registry", layer.digest());

            // 5) Publish to P2P/cache
            if (cache != null) {
                try (var in = new java.io.FileInputStream(tmp)) {
                    cache.put(blob.digest(), in, blob.size(), blob.mediaType());
                } catch (Exception ignored) {
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
        if (registryLimiter == null) return;
        try {
            registryLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for registry slot", e);
        }
    }

    private void releaseRegistry() {
        if (registryLimiter != null) {
            registryLimiter.release();
        }
    }
}

