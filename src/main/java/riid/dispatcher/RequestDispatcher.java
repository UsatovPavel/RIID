package riid.dispatcher;

import java.util.Objects;

import riid.cache.ImageDigest;
import riid.client.core.model.manifest.MediaType;

/**
 * Dispatcher decides источник (cache/P2P/registry) и вызывает соответствующие адаптеры.
 */
public interface RequestDispatcher {

    // Dispatcher-level image fetch by repository/tag/digest.
    FetchResult fetchImage(ImageRef ref);

    /**
     * Fetch a specific layer/config digest for a repository.
     */
    FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes, MediaType mediaType);

    /**
     * Base no-op implementation (placeholder).
     */
    class NoOp implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            Objects.requireNonNull(ref);
            throw new UnsupportedOperationException("Dispatcher not implemented");
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes, MediaType mediaType) {
            throw new UnsupportedOperationException("Dispatcher not implemented");
        }
    }
}

