package riid.app.error;

/**
 * Checked exception for OCI archive assembly failures within the app module.
 */
public final class OciArchiveException extends AppException {
    private static final long serialVersionUID = 1L;
    public OciArchiveException(AppError.OciError error, String message) {
        super(error, message);
    }

    public OciArchiveException(AppError.OciError error, String message, Throwable cause) {
        super(error, message, cause);
    }
}

