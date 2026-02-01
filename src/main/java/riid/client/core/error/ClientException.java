package riid.client.core.error;

import java.io.Serial;

/**
 * Domain exception carrying a ClientError.
 */
public class ClientException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ClientError clientError;

    public ClientException(ClientError error, String message) {
        super(message);
        this.clientError = error;
    }

    public ClientException(ClientError error, String message, Throwable cause) {
        super(message, cause);
        this.clientError = error;
    }

    public ClientError error() {
        return clientError;
    }
}

