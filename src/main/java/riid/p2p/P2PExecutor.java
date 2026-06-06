package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;

/**
 * P2P layer contract (stub).
 */
public interface P2PExecutor extends AutoCloseable {
    /**
     * Try to fetch blob from peers.
     *
     * @return path if found, empty otherwise
     */
    Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType) throws IOException;

    /**
     * Publish blob to peers (best effort).
     */
    void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType);

    /**
     * Releases optional P2P resources (channels, threads, etc).
     */
    @Override
    default void close() throws Exception {
        // no-op by default
    }

    /**
     * No-op implementation.
     */
    final class NoOp implements P2PExecutor {
        @Override
        public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType) {
            return Optional.empty();
        }

        @Override
        public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
            // no-op
        }
    }
}
