package riid.dispatcher;

/**
 * Dispatcher decides источник (cache/P2P/registry) и вызывает соответствующие адаптеры.
 */
public interface RequestDispatcher {

    FetchResult fetchImage(ImageRef ref);
}

