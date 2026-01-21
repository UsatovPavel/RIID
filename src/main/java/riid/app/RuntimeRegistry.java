package riid.app;

import riid.runtime.RuntimeAdapter;

import java.util.Map;
import java.util.Objects;

/**
 * Simple registry of runtime adapters with minimal helpers.
 */
public final class RuntimeRegistry {
    private final Map<String, RuntimeAdapter> runtimes;

    public RuntimeRegistry(Map<String, RuntimeAdapter> runtimes) {
        this.runtimes = Map.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
    }

    public RuntimeAdapter get(String runtimeId) {
        RuntimeAdapter adapter = runtimes.get(runtimeId);
        if (adapter == null) {
            throw new UncheckedAppException("Runtime adapter not found: " + runtimeId);
        }
        return adapter;
    }

}


