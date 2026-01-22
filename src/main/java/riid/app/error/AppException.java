package riid.app.error;

import java.util.Objects;

/**
 * Base checked exception for application domain errors.
 */
public class AppException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final AppError error;

    public AppException(AppError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    public AppException(AppError error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    public AppError error() {
        return error;
    }
}

