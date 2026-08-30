package riid.app.service;

import riid.app.core.error.AppError;
import riid.app.core.error.AppException;
import riid.runtime.adapter.RuntimeAdapter;
import riid.runtime.adapter.RuntimeId;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Simple registry of runtime adapters with minimal helpers.
 */
public final class RuntimeRegistry implements AutoCloseable {
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

    @Override
    @SuppressWarnings("PMD.CloseResource") // The registry owns and closes every adapter in this loop.
    public void close() throws IOException {
        IOException failure = null;
        for (RuntimeAdapter adapter : runtimes.values()) {
            try {
                adapter.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

}
