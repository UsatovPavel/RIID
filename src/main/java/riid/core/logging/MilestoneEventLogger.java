package riid.core.logging;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Thin wrapper over SLF4J fluent API for structured step events.
 */
public final class MilestoneEventLogger {
    private MilestoneEventLogger() {
    }

    public static Event info(Logger logger) {
        return new Event(Objects.requireNonNull(logger, "logger").atInfo());
    }

    public static Event warn(Logger logger) {
        return new Event(Objects.requireNonNull(logger, "logger").atWarn());
    }

    public static Event error(Logger logger) {
        return new Event(Objects.requireNonNull(logger, "logger").atError());
    }

    public static final class Event {
        private final LoggingEventBuilder builder;

        private Event(LoggingEventBuilder builder) {
            this.builder = Objects.requireNonNull(builder, "builder");
        }

        public Event addEvent(String event) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.EVENT, event), "builder");
            return this;
        }

        public Event addResult(String result) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.RESULT, result), "builder");
            return this;
        }

        public Event addDurationMs(long durationMs) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.DURATION_MS, durationMs), "builder");
            return this;
        }

        public Event addDurationFrom(long startedNs) {
            return addDurationMs((System.nanoTime() - startedNs) / 1_000_000L);
        }

        public Event addErrorKind(String errorKind) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.ERROR_KIND, errorKind), "builder");
            return this;
        }

        public Event addErrorCode(String errorCode) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.ERROR_CODE, errorCode), "builder");
            return this;
        }

        public Event addCause(Throwable cause) {
            Objects.requireNonNull(builder.setCause(cause), "builder");
            return this;
        }

        public void log(String message) {
            builder.log(message);
        }
    }
}
