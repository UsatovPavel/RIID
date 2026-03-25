package riid.core.logging;

import org.slf4j.MDC;

/**
 * Thin wrapper over MDC operations with shared keys.
 */
public final class MdcContext {
    private MdcContext() {
    }

    public static void putTraceId(String traceId) {
        MDC.put(LogContextKeys.TRACE_ID, traceId);
    }

    public static void putComponent(String component) {
        MDC.put(LogContextKeys.COMPONENT, component);
    }

    public static void putOperation(String operation) {
        MDC.put(LogContextKeys.OPERATION, operation);
    }

    public static String getOperation() {
        return MDC.get(LogContextKeys.OPERATION);
    }

    public static void restoreOperation(String previousOperation) {
        if (previousOperation == null || previousOperation.isBlank()) {
            MDC.remove(LogContextKeys.OPERATION);
            return;
        }
        MDC.put(LogContextKeys.OPERATION, previousOperation);
    }

    public static void clearOperation() {
        MDC.remove(LogContextKeys.OPERATION);
    }

    public static void clearRequestContext() {
        MDC.remove(LogContextKeys.OPERATION);
        MDC.remove(LogContextKeys.COMPONENT);
        MDC.remove(LogContextKeys.TRACE_ID);
    }
}
