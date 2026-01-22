package riid.dispatcher;

import riid.client.core.model.manifest.MediaType;
import java.util.Objects;

/**
 * No-op dispatcher placeholder.
 */
public final class NoOpRequestDispatcher implements RequestDispatcher {
    @Override
    public FetchResult fetchImage(ImageRef ref) {
        Objects.requireNonNull(ref);
        throw new UnsupportedOperationException("Dispatcher not implemented");
    }

    @Override
    public FetchResult fetchLayer(String repository,
                                  riid.cache.ImageDigest digest,
                                  long sizeBytes,
                                  MediaType mediaType) {
        throw new UnsupportedOperationException("Dispatcher not implemented");
    }
}


