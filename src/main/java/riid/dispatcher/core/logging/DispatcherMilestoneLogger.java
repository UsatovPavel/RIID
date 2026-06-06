package riid.dispatcher.core.logging;

import java.util.Objects;

import org.slf4j.Logger;

import riid.core.logging.MdcContext;
import riid.core.logging.MilestoneEventLogger;
import riid.core.logging.MilestoneEventLogger.EventType;
import riid.core.logging.MilestoneEventLogger.ResultType;

public final class DispatcherMilestoneLogger {
    private final Logger logger;

    public DispatcherMilestoneLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void sourceSelectFromCache(long startedNs) {
        withOperation(EventType.SOURCE_SELECT, () -> MilestoneEventLogger.info(logger).addEvent(EventType.SOURCE_SELECT)
                .addResult(ResultType.SUCCESS).addDurationMs(durationMs(startedNs)).log("Source selected: cache"));
    }

    public void sourceSelectFromP2p(long startedNs) {
        withOperation(EventType.SOURCE_SELECT, () -> MilestoneEventLogger.info(logger).addEvent(EventType.SOURCE_SELECT)
                .addResult(ResultType.SUCCESS).addDurationMs(durationMs(startedNs)).log("Source selected: p2p"));
    }

    public void sourceSelectFromRegistry(long startedNs) {
        withOperation(EventType.SOURCE_SELECT, () -> MilestoneEventLogger.info(logger).addEvent(EventType.SOURCE_SELECT)
                .addResult(ResultType.SUCCESS).addDurationMs(durationMs(startedNs)).log("Source selected: registry"));
    }

    public void sourceFetchFromCache(long startedNs) {
        withOperation(EventType.SOURCE_FETCH, () -> MilestoneEventLogger.info(logger).addEvent(EventType.SOURCE_FETCH)
                .addResult(ResultType.SUCCESS).addDurationMs(durationMs(startedNs)).log("Source fetched: cache"));
    }

    public void sourceFetchFromP2p(long startedNs) {
        withOperation(EventType.SOURCE_FETCH, () -> MilestoneEventLogger.info(logger).addEvent(EventType.SOURCE_FETCH)
                .addResult(ResultType.SUCCESS).addDurationMs(durationMs(startedNs)).log("Source fetched: p2p"));
    }

    public void sourceFetchFromRegistry(long startedNs) {
        withOperation(EventType.SOURCE_FETCH, () -> MilestoneEventLogger.info(logger).addEvent(EventType.SOURCE_FETCH)
                .addResult(ResultType.SUCCESS).addDurationMs(durationMs(startedNs)).log("Source fetched: registry"));
    }

    public void sourceFetchFailed(long startedNs, Throwable cause) {
        withOperation(EventType.SOURCE_FETCH,
                () -> MilestoneEventLogger.error(logger).addCause(cause).addEvent(EventType.SOURCE_FETCH)
                        .addResult(ResultType.ERROR).addDurationMs(durationMs(startedNs)).addErrorKind("INTERNAL")
                        .addErrorCode("SOURCE_FETCH_FAILED").log("Source fetch failed"));
    }

    private static long durationMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static void withOperation(EventType operation, Runnable runnable) {
        String previous = MdcContext.getOperation();
        MdcContext.putOperation(operation.value());
        try {
            runnable.run();
        } finally {
            MdcContext.restoreOperation(previous);
        }
    }
}
