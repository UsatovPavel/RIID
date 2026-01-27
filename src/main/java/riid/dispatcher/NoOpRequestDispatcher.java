package riid.dispatcher;

import java.util.Objects;

import riid.client.core.model.manifest.MediaType;

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
    public FetchResult fetchLayer(RepositoryName repository,
                                  riid.cache.ImageDigest digest,
                                  long sizeBytes,
                                  MediaType mediaType) {
        throw new UnsupportedOperationException("Dispatcher not implemented");
    }
}


