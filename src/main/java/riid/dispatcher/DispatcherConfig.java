package riid.dispatcher;

/**
 * Configuration for RequestDispatcher.
 */
public final class DispatcherConfig {
    private final int maxConcurrentRegistry;

    private DispatcherConfig(Builder b) {
        this.maxConcurrentRegistry = b.maxConcurrentRegistry;
    }

    public int maxConcurrentRegistry() {
        return maxConcurrentRegistry;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxConcurrentRegistry = 4; // default concurrency limit to registry

        public Builder maxConcurrentRegistry(int v) {
            this.maxConcurrentRegistry = v;
            return this;
        }

        public DispatcherConfig build() {
            return new DispatcherConfig(this);
        }
    }
}


