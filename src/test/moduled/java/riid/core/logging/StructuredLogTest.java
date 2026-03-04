package riid.core.logging;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class StructuredLogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void structuredEventContainsRequiredFields() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("riid.test.StructuredLog.requiredFields");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StructuredLog.error(
                    logger,
                    "request.finish",
                    "app",
                    "cli.run",
                    "error",
                    123L,
                    "REQUEST_FAILED",
                    "IllegalStateException",
                    StructuredLog.fields("exit_code", 1)
            );

            Assertions.assertFalse(appender.list.isEmpty());
            String json = appender.list.getFirst().getFormattedMessage();
            Map<?, ?> payload = MAPPER.readValue(json, Map.class);

            Assertions.assertTrue(payload.containsKey("timestamp"));
            Assertions.assertTrue(payload.containsKey("level"));
            Assertions.assertTrue(payload.containsKey("event"));
            Assertions.assertTrue(payload.containsKey("trace_id"));
            Assertions.assertTrue(payload.containsKey("component"));
            Assertions.assertTrue(payload.containsKey("operation"));
            Assertions.assertTrue(payload.containsKey("result"));
            Assertions.assertTrue(payload.containsKey("duration_ms"));
            Assertions.assertTrue(payload.containsKey("error_code"));
            Assertions.assertTrue(payload.containsKey("error_kind"));
            Assertions.assertEquals("IllegalStateException", payload.get("error_kind"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void errorKindDefaultsToNoneWhenBlankOrNull() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("riid.test.StructuredLog.errorKind");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StructuredLog.error(
                    logger,
                    "request.finish",
                    "app",
                    "cli.run",
                    "error",
                    5L,
                    "REQUEST_FAILED",
                    null,
                    StructuredLog.fields()
            );

            String json = appender.list.getFirst().getFormattedMessage();
            Map<?, ?> payload = MAPPER.readValue(json, Map.class);
            Assertions.assertEquals("none", payload.get("error_kind"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
