package riid.app;

/**
 * Common HTTP status codes used across services.
 */
public enum StatusCodes {
    OK(200),
    UNAUTHORIZED(401),
    NOT_FOUND(404);

    private final int code;

    StatusCodes(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}

