package riid.dispatcher;

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
    public FetchResult fetchLayer(String repository, String digest, long sizeBytes, String mediaType) {
        throw new UnsupportedOperationException("Dispatcher not implemented");
    }
}


