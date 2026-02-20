package riid.p2p;

import java.io.IOException;

/**
 * Typed Dragonfly adapter failure for fallback decisions.
 */
public final class DragonflyClientException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum ErrorKind {
        UNHEALTHY,
        TIMEOUT,
        PROCESS_FAILED,
        IO
    }

    private final ErrorKind errorKind;

    public DragonflyClientException(ErrorKind errorKind, String message) {
        super(message);
        this.errorKind = errorKind;
    }

    public DragonflyClientException(ErrorKind errorKind, String message, Throwable cause) {
        super(message, cause);
        this.errorKind = errorKind;
    }

    public ErrorKind kind() {
        return errorKind;
    }
}
