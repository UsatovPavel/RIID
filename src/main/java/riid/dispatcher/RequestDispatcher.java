package riid.dispatcher;

import java.util.Objects;

/**
 * Dispatcher decides источник (cache/P2P/registry) и вызывает соответствующие адаптеры.
 */
public interface RequestDispatcher {

    FetchResult fetchImage(ImageRef ref);

    /**
     * Base no-op implementation (placeholder).
     */
    class NoOp implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            Objects.requireNonNull(ref);
            throw new UnsupportedOperationException("Dispatcher not implemented");
        }
    }
}

