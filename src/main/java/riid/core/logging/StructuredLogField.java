package riid.core.logging;

/**
 * Required top-level fields for structured log events.
 */
public enum StructuredLogField {
    TIMESTAMP("timestamp"),
    LEVEL("level"),
    EVENT("event"),
    TRACE_ID("trace_id"),
    COMPONENT("component"),
    OPERATION("operation"),
    RESULT("result"),
    DURATION_MS("duration_ms"),
    ERROR_CODE("error_code"),
    ERROR_KIND("error_kind");

    private final String key;

    StructuredLogField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
