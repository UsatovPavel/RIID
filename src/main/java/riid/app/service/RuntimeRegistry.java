package riid.app.service;

import riid.app.core.error.AppError;
import riid.app.core.error.AppException;
import riid.runtime.adapter.RuntimeAdapter;
import riid.runtime.adapter.RuntimeId;

import java.util.Map;
import java.util.Objects;

/**
 * Simple registry of runtime adapters with minimal helpers.
 */
public final class RuntimeRegistry {
    private final Map<RuntimeId, RuntimeAdapter> runtimes;

    public RuntimeRegistry(Map<RuntimeId, RuntimeAdapter> runtimes) {
        this.runtimes = Map.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
    }

    public RuntimeAdapter get(RuntimeId runtimeId) {
        RuntimeAdapter adapter = runtimes.get(runtimeId);
        if (adapter == null) {
            String msg = AppError.RuntimeErrorKind.ADAPTER_NOT_FOUND.format(runtimeId);
            throw new AppException(new AppError.RuntimeError(AppError.RuntimeErrorKind.ADAPTER_NOT_FOUND, msg), msg);
        }
        return adapter;
    }

}
