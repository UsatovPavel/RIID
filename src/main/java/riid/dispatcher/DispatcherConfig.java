package riid.dispatcher;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for RequestDispatcher.
 */
public record DispatcherConfig(@JsonProperty("maxConcurrentRegistry") int maxConcurrentRegistry) {
    private static final int DEFAULT_MAX_CONCURRENT = 4;

    public DispatcherConfig() {
        this(DEFAULT_MAX_CONCURRENT);
    }

    public DispatcherConfig(int maxConcurrentRegistry) {
        this.maxConcurrentRegistry = maxConcurrentRegistry > 0 ? maxConcurrentRegistry : DEFAULT_MAX_CONCURRENT;
    }
}


