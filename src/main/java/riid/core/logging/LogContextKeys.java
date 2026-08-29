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

    /**
     * Layer this line is about. Set on the pull task, so every line a layer
     * produces carries it - source.select and source.fetch included.
     */
    public static final String LAYER_DIGEST = "layer_digest";

    private LogContextKeys() {
    }
}
