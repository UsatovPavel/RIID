package riid.core.logging;

/**
 * Shared structured logging keys.
 */
public final class LogContextKeys {
    public static final String TRACE_ID = "trace_id";
    public static final String COMPONENT = "component";
    public static final String OPERATION = "operation";

    public static final String EVENT = "event";
    public static final String RESULT = "result";
    public static final String DURATION_MS = "duration_ms";
    public static final String ERROR_KIND = "error_kind";
    public static final String ERROR_CODE = "error_code";

    private LogContextKeys() {
    }
}
