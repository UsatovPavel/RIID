package riid.app.error;

/**
 * Domain error hierarchy for the app module.
 */
public sealed interface AppError permits AppError.Oci, AppError.RuntimeError {
    record Oci(OciErrorKind kind, String message) implements AppError { }

    enum OciErrorKind {
        RESOURCE_NOT_FOUND("Resource not found: %s"),
        RESOURCE_READ_FAILED("Failed to read resource: %s");

        private final String template;

        OciErrorKind(String template) {
            this.template = template;
        }

        public String format(Object... args) {
            return String.format(template, args);
        }
    }

    record RuntimeError(RuntimeErrorKind kind, String message) implements AppError { }

    enum RuntimeErrorKind {
        ADAPTER_NOT_FOUND("Runtime adapter not found: %s"),
        LOAD_FAILED("Failed to load image into runtime %s");

        private final String template;

        RuntimeErrorKind(String template) {
            this.template = template;
        }

        public String format(Object... args) {
            return String.format(template, args);
        }
    }
}

