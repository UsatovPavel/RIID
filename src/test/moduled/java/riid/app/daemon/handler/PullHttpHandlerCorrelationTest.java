// SPDX-License-Identifier: Apache-2.0

package riid.app.daemon.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpFields;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PullHttpHandlerCorrelationTest {

    private static final Pattern UUID_RE = Pattern
            .compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Test
    void isValidClientTraceIdAcceptsSafeAscii() {
        assertTrue(PullHttpHandler.isValidClientTraceId("a-b_1.2:segment"));
        assertTrue(PullHttpHandler.isValidClientTraceId("abc"));
    }

    @Test
    void isValidClientTraceIdRejectsEmptyOrLongOrUnsafe() {
        assertFalse(PullHttpHandler.isValidClientTraceId(""));
        assertFalse(PullHttpHandler.isValidClientTraceId(" "));
        assertFalse(PullHttpHandler.isValidClientTraceId("a/b"));
        assertFalse(PullHttpHandler.isValidClientTraceId("a\nb"));
        assertFalse(PullHttpHandler.isValidClientTraceId("a".repeat(129)));
    }

    @Test
    void traceIdFromHttpFieldsPrefersXTraceId() {
        HttpFields.Mutable h = HttpFields.build();
        h.add("X-Trace-Id", "  t-1  ");
        h.add("X-Request-Id", "ignored");
        assertEquals("t-1", PullHttpHandler.traceIdFromHttpFields(h));
    }

    @Test
    void traceIdFromHttpFieldsFallsBackToXRequestId() {
        HttpFields.Mutable h = HttpFields.build();
        h.add("X-Request-Id", "req-42");
        assertEquals("req-42", PullHttpHandler.traceIdFromHttpFields(h));
    }

    @Test
    void traceIdFromHttpFieldsInvalidHeaderGeneratesUuid() {
        HttpFields.Mutable h = HttpFields.build();
        h.add("X-Trace-Id", "../../etc");
        String id = PullHttpHandler.traceIdFromHttpFields(h);
        assertNotNull(id);
        assertTrue(UUID_RE.matcher(id).matches(), id);
    }

    @Test
    void traceIdFromHttpFieldsNullHeadersGeneratesUuid() {
        String id = PullHttpHandler.traceIdFromHttpFields(null);
        assertNotNull(id);
        assertTrue(UUID_RE.matcher(id).matches(), id);
    }

    @Test
    void mdcIsInheritedAfterSimulatedWorkerRestore() {
        HttpFields.Mutable h = HttpFields.build();
        h.add("X-Trace-Id", "corr-mdc-test");
        String traceId = PullHttpHandler.traceIdFromHttpFields(h);

        MDC.put("trace_id", traceId);
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        MDC.clear();

        AtomicReference<String> seen = new AtomicReference<>();
        Runnable worker = () -> {
            try {
                if (snapshot != null) {
                    MDC.setContextMap(new HashMap<>(snapshot));
                }
                seen.set(MDC.get("trace_id"));
            } finally {
                MDC.clear();
            }
        };
        Thread t = Thread.ofVirtual().start(worker);
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        assertEquals("corr-mdc-test", seen.get());
    }
}
