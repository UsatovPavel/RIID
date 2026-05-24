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

    public enum EventType {
        REQUEST_START("request.start"), REQUEST_FINISH("request.finish"), ENGINE_IMPORT("engine.import"), SOURCE_SELECT(
                "source.select"), SOURCE_FETCH(
                        "source.fetch"), ARCHIVE_BUILD("archive.build"), MANIFEST_FETCH("manifest.fetch");

        private final String wireValue;

        EventType(String value) {
            this.wireValue = value;
        }

        public String value() {
            return wireValue;
        }
    }

    public enum ResultType {
        SUCCESS("success"), ERROR("error");

        private final String wireValue;

        ResultType(String value) {
            this.wireValue = value;
        }

        public String value() {
            return wireValue;
        }
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
        private enum InternalName {
            BUILDER("builder");

            private final String keyName;

            InternalName(String value) {
                this.keyName = value;
            }

            String value() {
                return keyName;
            }
        }

        private final LoggingEventBuilder builder;

        private Event(LoggingEventBuilder builder) {
            this.builder = Objects.requireNonNull(builder, InternalName.BUILDER.value());
        }

        public Event addEvent(EventType event) {
            return addEvent(Objects.requireNonNull(event, "event").value());
        }

        public Event addEvent(String event) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.EVENT, event), InternalName.BUILDER.value());
            return this;
        }

        public Event addResult(ResultType result) {
            return addResult(Objects.requireNonNull(result, "result").value());
        }

        public Event addResult(String result) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.RESULT, result), InternalName.BUILDER.value());
            return this;
        }

        public Event addDurationMs(long durationMs) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.DURATION_MS, durationMs),
                    InternalName.BUILDER.value());
            return this;
        }

        public Event addDurationFrom(long startedNs) {
            return addDurationMs((System.nanoTime() - startedNs) / 1_000_000L);
        }

        public Event addErrorKind(String errorKind) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.ERROR_KIND, errorKind),
                    InternalName.BUILDER.value());
            return this;
        }

        public Event addErrorCode(String errorCode) {
            Objects.requireNonNull(builder.addKeyValue(LogContextKeys.ERROR_CODE, errorCode),
                    InternalName.BUILDER.value());
            return this;
        }

        public Event addCause(Throwable cause) {
            Objects.requireNonNull(builder.setCause(cause), InternalName.BUILDER.value());
            return this;
        }

        public void log(String message) {
            builder.log(message);
        }
    }
}
