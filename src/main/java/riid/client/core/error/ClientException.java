package riid.client.core.error;

/**
 * Domain exception carrying a ClientError.
 */
public class ClientException extends RuntimeException {
    private final ClientError error;

    public ClientException(ClientError error, String message) {
        super(message);
        this.error = error;
    }

    public ClientException(ClientError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public ClientError error() {
        return error;
    }
}

