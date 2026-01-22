package riid.app.error;

/**
 * Checked exception for OCI archive assembly failures within the app module.
 */
public final class OciArchiveException extends AppException {
    public OciArchiveException(AppError.Oci error, String message) {
        super(error, message);
    }

    public OciArchiveException(AppError.Oci error, String message, Throwable cause) {
        super(error, message, cause);
    }
}

