package riid.app.daemon.handler;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.http.HttpStatus;

import riid.app.core.error.AppError;
import riid.app.core.error.AppException;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

/**
 * Turns pull failures from the image loading path and registry client into HTTP status and JSON for the daemon POST /pull endpoint.
 *
 * Registry HTTP 404 on manifest or blob becomes HTTP 404 with code registry_not_found and a single fixed user-facing message.
 *
 * Other registry 4xx (401, 403, 409, …) still become HTTP 404 with that same message, but a different JSON code
 * (registry_response_unauthorized, registry_response_forbidden, registry_response_other) so callers can tell cases apart
 * without exposing the registry line status.
 *
 * Registry blocked by allowedRegistries becomes HTTP 403 with registry_not_allowed. Missing runtime adapter becomes HTTP 422 with adapter_not_found.
 *
 * Registry 5xx is not handled here; the handler responds with HTTP 500 and pull_failed.
 */
public final class DaemonPullErrorMapper {

    /** Same message for true registry miss and for other registry 4xx that are mapped to HTTP 404. */
    public static final String REGISTRY_NOT_FOUND_MESSAGE = "Image, tag or digest not found in registry";

    /**
     * JSON "code" values for mapped POST /pull errors. Nested here so the wire contract stays next to the mapper.
     */
    public enum PullErrorCode {

        REGISTRY_NOT_FOUND("registry_not_found"),

        REGISTRY_NOT_ALLOWED("registry_not_allowed"),

        ADAPTER_NOT_FOUND("adapter_not_found"),

        REGISTRY_RESPONSE_UNAUTHORIZED("registry_response_unauthorized"),

        REGISTRY_RESPONSE_FORBIDDEN("registry_response_forbidden"),

        REGISTRY_RESPONSE_OTHER("registry_response_other");

        private final String jsonValue;

        PullErrorCode(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }
    }

    public record MappedHttpError(int httpStatus, PullErrorCode code, String message) {
    }

    private DaemonPullErrorMapper() {
    }

    /**
     * @return mapped client-facing error, or empty so the handler uses HTTP 500 pull_failed
     */
    public static Optional<MappedHttpError> map(Throwable throwable) {
        Throwable t = unwrapExecution(throwable);
        if (t instanceof AppException appException) {
            Optional<MappedHttpError> fromApp = mapAppException(appException);
            if (fromApp.isPresent()) {
                return fromApp;
            }
        }
        if (t instanceof ClientException clientException) {
            return mapClientException(clientException);
        }
        return Optional.empty();
    }

    private static Throwable unwrapExecution(Throwable t) {
        Throwable cur = t;
        while (cur instanceof ExecutionException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static Optional<MappedHttpError> mapAppException(AppException e) {
        if (!(e.error() instanceof AppError.RuntimeError runtimeError)) {
            return Optional.empty();
        }
        return switch (runtimeError.kind()) {
            case REGISTRY_NOT_ALLOWED -> Optional.of(new MappedHttpError(
                    HttpStatus.FORBIDDEN_403,
                    PullErrorCode.REGISTRY_NOT_ALLOWED,
                    safeMessage(e)));
            case ADAPTER_NOT_FOUND -> Optional.of(new MappedHttpError(
                    HttpStatus.UNPROCESSABLE_ENTITY_422,
                    PullErrorCode.ADAPTER_NOT_FOUND,
                    safeMessage(e)));
            case LOAD_FAILED -> Optional.empty();
        };
    }

    private static Optional<MappedHttpError> mapClientException(ClientException e) {
        if (!(e.error() instanceof ClientError.Http http) || http.status() == null) {
            return Optional.empty();
        }
        int st = http.status();
        if (st == HttpStatus.NOT_FOUND_404) {
            return Optional.of(new MappedHttpError(
                    HttpStatus.NOT_FOUND_404,
                    PullErrorCode.REGISTRY_NOT_FOUND,
                    REGISTRY_NOT_FOUND_MESSAGE));
        }
        if (st == HttpStatus.UNAUTHORIZED_401) {
            return Optional.of(new MappedHttpError(
                    HttpStatus.NOT_FOUND_404,
                    PullErrorCode.REGISTRY_RESPONSE_UNAUTHORIZED,
                    REGISTRY_NOT_FOUND_MESSAGE));
        }
        if (st == HttpStatus.FORBIDDEN_403) {
            return Optional.of(new MappedHttpError(
                    HttpStatus.NOT_FOUND_404,
                    PullErrorCode.REGISTRY_RESPONSE_FORBIDDEN,
                    REGISTRY_NOT_FOUND_MESSAGE));
        }
        if (st >= 400 && st < 500) {
            return Optional.of(new MappedHttpError(
                    HttpStatus.NOT_FOUND_404,
                    PullErrorCode.REGISTRY_RESPONSE_OTHER,
                    REGISTRY_NOT_FOUND_MESSAGE));
        }
        return Optional.empty();
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
