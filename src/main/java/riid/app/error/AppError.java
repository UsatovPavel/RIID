package riid.app.error;

/**
 * Domain error hierarchy for the app module.
 */
public sealed interface AppError permits AppError.Oci, AppError.Runtime {
    record Oci(OciKind kind, String message) implements AppError { }

    enum OciKind {
        RESOURCE_NOT_FOUND("Resource not found: %s"),
        RESOURCE_READ_FAILED("Failed to read resource: %s");

        private final String template;

        OciKind(String template) {
            this.template = template;
        }

        public String format(Object... args) {
            return String.format(template, args);
        }
    }

    record Runtime(RuntimeKind kind, String message) implements AppError { }

    enum RuntimeKind {
        ADAPTER_NOT_FOUND("Runtime adapter not found: %s"),
        LOAD_FAILED("Failed to load image into runtime %s");

        private final String template;

        RuntimeKind(String template) {
            this.template = template;
        }

        public String format(Object... args) {
            return String.format(template, args);
        }
    }
}

