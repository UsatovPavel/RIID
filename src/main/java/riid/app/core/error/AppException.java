package riid.app.core.error;

import java.util.Objects;

/**
 * Base checked exception for application domain errors.
 */
public class AppException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final AppError appError;

    public AppException(AppError error, String message) {
        super(message);
        this.appError = Objects.requireNonNull(error, "error");
    }

    public AppException(AppError error, String message, Throwable cause) {
        super(message, cause);
        this.appError = Objects.requireNonNull(error, "error");
    }

    public AppError error() {
        return appError;
    }

    public String errorCode() {
        if (appError instanceof AppError.RuntimeError runtimeError) {
            return runtimeError.kind().name();
        }
        if (appError instanceof AppError.OciError ociError) {
            return ociError.kind().name();
        }
        return "APP_ERROR";
    }
}
