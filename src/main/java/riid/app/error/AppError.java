package riid.app.error;

/**
 * Domain error hierarchy for the app module.
 */
public sealed interface AppError permits AppError.Oci, AppError.Runtime {
    record Oci(OciKind kind, String message) implements AppError { }

    enum OciKind { RESOURCE_NOT_FOUND, RESOURCE_READ_FAILED }

    record Runtime(RuntimeKind kind, String message) implements AppError { }

    enum RuntimeKind { ADAPTER_NOT_FOUND, LOAD_FAILED }
}

