package riid.core.logging;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal structured log helper for critical-path events.
 */
public final class StructuredLog {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NONE = "none";
    private static final AtomicReference<Clock> CLOCK = new AtomicReference<>(Clock.systemUTC());

    private StructuredLog() {
    }

    public static void info(Logger logger,
                            String event,
                            String component,
                            String operation,
                            String result,
                            long durationMs,
                            String errorCode,
                            String errorKind,
                            Map<String, Object> extraFields) {
        log(logger,
                Level.INFO,
                event,
                component,
                operation,
                result,
                durationMs,
                errorCode,
                errorKind,
                extraFields);
    }

    public static void warn(Logger logger,
                            String event,
                            String component,
                            String operation,
                            String result,
                            long durationMs,
                            String errorCode,
                            String errorKind,
                            Map<String, Object> extraFields) {
        log(logger,
                Level.WARN,
                event,
                component,
                operation,
                result,
                durationMs,
                errorCode,
                errorKind,
                extraFields);
    }

    public static void error(Logger logger,
                             String event,
                             String component,
                             String operation,
                             String result,
                             long durationMs,
                             String errorCode,
                             String errorKind,
                             Map<String, Object> extraFields) {
        log(logger,
                Level.ERROR,
                event,
                component,
                operation,
                result,
                durationMs,
                errorCode,
                errorKind,
                extraFields);
    }

    public static Map<String, Object> fields(Object... values) {
        if (values == null || values.length == 0) {
            return Map.of();
        }
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("fields requires even number of values");
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            String key = Objects.toString(values[i], null);
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("field key must be non-blank");
            }
            map.put(key, values[i + 1]);
        }
        return Map.copyOf(map);
    }

    public static void useClock(Clock clock) {
        CLOCK.set(Objects.requireNonNull(clock, "clock"));
    }

    public static void resetClock() {
        CLOCK.set(Clock.systemUTC());
    }

    private static void log(Logger logger,
                            Level level,
                            String event,
                            String component,
                            String operation,
                            String result,
                            long durationMs,
                            String errorCode,
                            String errorKind,
                            Map<String, Object> extraFields) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(StructuredLogField.TIMESTAMP.key(), CLOCK.get().instant().toString());
        payload.put(StructuredLogField.LEVEL.key(), level.toString());
        payload.put(StructuredLogField.EVENT.key(), event);
        payload.put(StructuredLogField.TRACE_ID.key(), traceId());
        payload.put(StructuredLogField.COMPONENT.key(), component);
        payload.put(StructuredLogField.OPERATION.key(), operation);
        payload.put(StructuredLogField.RESULT.key(), result);
        payload.put(StructuredLogField.DURATION_MS.key(), Math.max(durationMs, 0L));
        payload.put(StructuredLogField.ERROR_CODE.key(), normalize(errorCode));
        payload.put(StructuredLogField.ERROR_KIND.key(), normalize(errorKind));
        if (extraFields != null && !extraFields.isEmpty()) {
            payload.putAll(extraFields);
        }

        String message = toJson(payload);
        switch (level) {
            case ERROR -> logger.error(message);
            case WARN -> logger.warn(message);
            case INFO, DEBUG, TRACE -> logger.info(message);
        }
    }

    private static String traceId() {
        String value = MDC.get("trace_id");
        return value == null || value.isBlank() ? NONE : value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? NONE : value;
    }

    private static String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return payload.toString();
        }
    }
}
