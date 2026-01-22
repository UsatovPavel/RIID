package riid.app;

import riid.app.error.AppError;
import riid.app.error.AppException;
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
            String msg = AppError.RuntimeKind.ADAPTER_NOT_FOUND.format(runtimeId);
            throw new AppException(
                    new AppError.Runtime(AppError.RuntimeKind.ADAPTER_NOT_FOUND, msg),
                    msg);
        }
        return adapter;
    }

}


