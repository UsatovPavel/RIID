package riid.core.logging;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;

class MilestoneEventLoggerTest {
    @Test
    void stepSuccessContainsEventResultAndDuration() {
        CapturingBuilder builder = new CapturingBuilder();
        Logger logger = loggerWithBuilder(builder);

        MilestoneEventLogger.info(logger)
                .addEvent("manifest.fetch")
                .addResult("success")
                .addDurationMs(42L)
                .log("Manifest fetched");

        assertEquals("manifest.fetch", builder.keyValues.get(LogContextKeys.EVENT));
        assertEquals("success", builder.keyValues.get(LogContextKeys.RESULT));
        assertEquals(42L, builder.keyValues.get(LogContextKeys.DURATION_MS));
        assertEquals("Manifest fetched", builder.message);
    }

    @Test
    void errorBranchContainsKindCodeAndCause() {
        CapturingBuilder builder = new CapturingBuilder();
        Logger logger = loggerWithBuilder(builder);
        RuntimeException failure = new RuntimeException("boom");

        MilestoneEventLogger.error(logger)
                .addCause(failure)
                .addEvent("engine.import")
                .addResult("error")
                .addDurationMs(5L)
                .addErrorKind("RUNTIME")
                .addErrorCode("LOAD_FAILED")
                .log("Runtime import failed");

        assertEquals("engine.import", builder.keyValues.get(LogContextKeys.EVENT));
        assertEquals("error", builder.keyValues.get(LogContextKeys.RESULT));
        assertEquals(5L, builder.keyValues.get(LogContextKeys.DURATION_MS));
        assertEquals("RUNTIME", builder.keyValues.get(LogContextKeys.ERROR_KIND));
        assertEquals("LOAD_FAILED", builder.keyValues.get(LogContextKeys.ERROR_CODE));
        assertEquals("Runtime import failed", builder.message);
        assertSame(failure, builder.cause);
    }

    private static Logger loggerWithBuilder(CapturingBuilder builder) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("atInfo".equals(name) || "atWarn".equals(name) || "atError".equals(name)) {
                        return builder;
                    }
                    if ("isEnabledForLevel".equals(name)) {
                        return true;
                    }
                    if ("getName".equals(name)) {
                        return "test-logger";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType.equals(boolean.class)) {
                        return true;
                    }
                    if (returnType.equals(void.class)) {
                        return null;
                    }
                    return null;
                });
    }

    private static final class CapturingBuilder implements LoggingEventBuilder {
        private final Map<String, Object> keyValues = new LinkedHashMap<>();
        private Throwable cause;
        private String message;

        @Override
        public LoggingEventBuilder setCause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        @Override
        public LoggingEventBuilder addMarker(Marker marker) {
            return this;
        }

        @Override
        public LoggingEventBuilder addArgument(Object p) {
            return this;
        }

        @Override
        public LoggingEventBuilder addArgument(java.util.function.Supplier<?> objectSupplier) {
            return this;
        }

        @Override
        public LoggingEventBuilder addKeyValue(String key, Object value) {
            keyValues.put(key, value);
            return this;
        }

        @Override
        public LoggingEventBuilder addKeyValue(String key, java.util.function.Supplier<Object> valueSupplier) {
            keyValues.put(key, valueSupplier.get());
            return this;
        }

        @Override
        public LoggingEventBuilder setMessage(String message) {
            this.message = message;
            return this;
        }

        @Override
        public LoggingEventBuilder setMessage(java.util.function.Supplier<String> messageSupplier) {
            this.message = messageSupplier.get();
            return this;
        }

        @Override
        public void log() {
            // no-op
        }

        @Override
        public void log(String message) {
            this.message = message;
        }

        @Override
        public void log(String format, Object arg) {
            this.message = format + " " + arg;
        }

        @Override
        public void log(String format, Object arg0, Object arg1) {
            this.message = format + " " + arg0 + " " + arg1;
        }

        @Override
        public void log(String format, Object... arguments) {
            this.message = format;
        }

        @Override
        public void log(java.util.function.Supplier<String> messageSupplier) {
            this.message = messageSupplier.get();
        }
    }
}
