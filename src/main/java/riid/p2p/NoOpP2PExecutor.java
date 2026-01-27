package riid.p2p;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;

import java.nio.file.Path;
import java.util.Optional;

/**
 * No-op P2P executor.
 */
public final class NoOpP2PExecutor implements P2PExecutor {
    @Override
    public Optional<Path> fetch(ImageDigest digest, long size, CacheMediaType mediaType) {
        return Optional.empty();
    }

    @Override
    public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
        // no-op
    }
}


