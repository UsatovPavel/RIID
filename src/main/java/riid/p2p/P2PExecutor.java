package riid.p2p;

import riid.cache.CacheMediaType;
import riid.cache.ImageDigest;

import java.nio.file.Path;
import java.util.Optional;

/**
 * P2P layer contract (stub).
 */
public interface P2PExecutor {
    /**
     * Try to fetch blob from peers.
     * @return path if found, empty otherwise
     */
    Optional<Path> fetch(ImageDigest digest, long size, CacheMediaType mediaType);

    /**
     * Publish blob to peers (best effort).
     */
    void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType);

    /**
     * No-op implementation.
     */
    final class NoOp implements P2PExecutor {
        @Override
        public Optional<Path> fetch(ImageDigest digest, long size, CacheMediaType mediaType) {
            return Optional.empty();
        }

        @Override
        public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
            // no-op
        }
    }
}
