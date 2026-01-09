package riid.p2p;

import java.util.Optional;

/**
 * P2P layer contract (stub).
 */
public interface P2PExecutor {
    /**
     * Try to fetch blob from peers.
     * @return path if found, empty otherwise
     */
    Optional<String> fetch(String digest, long size, String mediaType);

    /**
     * Publish blob to peers (best effort).
     */
    void publish(String digest, String path, long size, String mediaType);

    class NoOp implements P2PExecutor {
        @Override
        public Optional<String> fetch(String digest, long size, String mediaType) {
            return Optional.empty();
        }

        @Override
        public void publish(String digest, String path, long size, String mediaType) {
            // no-op
        }
    }
}


