package riid.dispatcher;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for RequestDispatcher.
 */
public record DispatcherConfig(@JsonProperty("maxConcurrentRegistry") int maxConcurrentRegistry) {
    public DispatcherConfig() {
        this(4);
    }
}


