package riid.dispatcher.core.logging;

import java.util.Objects;

import org.slf4j.Logger;

import riid.core.logging.MdcContext;
import riid.core.logging.MilestoneEventLogger;

public final class DispatcherMilestoneLogger {
    private final Logger logger;

    public DispatcherMilestoneLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void sourceSelectFromCache(long startedNs) {
        withOperation("source.select", () -> MilestoneEventLogger.info(logger)
                .addEvent("source.select")
                .addResult("success")
                .addDurationMs(durationMs(startedNs))
                .log("Source selected: cache"));
    }

    public void sourceSelectFromP2p(long startedNs) {
        withOperation("source.select", () -> MilestoneEventLogger.info(logger)
                .addEvent("source.select")
                .addResult("success")
                .addDurationMs(durationMs(startedNs))
                .log("Source selected: p2p"));
    }

    public void sourceSelectFromRegistry(long startedNs) {
        withOperation("source.select", () -> MilestoneEventLogger.info(logger)
                .addEvent("source.select")
                .addResult("success")
                .addDurationMs(durationMs(startedNs))
                .log("Source selected: registry"));
    }

    public void sourceFetchFromCache(long startedNs) {
        withOperation("source.fetch", () -> MilestoneEventLogger.info(logger)
                .addEvent("source.fetch")
                .addResult("success")
                .addDurationMs(durationMs(startedNs))
                .log("Source fetched: cache"));
    }

    public void sourceFetchFromP2p(long startedNs) {
        withOperation("source.fetch", () -> MilestoneEventLogger.info(logger)
                .addEvent("source.fetch")
                .addResult("success")
                .addDurationMs(durationMs(startedNs))
                .log("Source fetched: p2p"));
    }

    public void sourceFetchFromRegistry(long startedNs) {
        withOperation("source.fetch", () -> MilestoneEventLogger.info(logger)
                .addEvent("source.fetch")
                .addResult("success")
                .addDurationMs(durationMs(startedNs))
                .log("Source fetched: registry"));
    }

    public void sourceFetchFailed(long startedNs, Throwable cause) {
        withOperation("source.fetch", () -> MilestoneEventLogger.error(logger)
                .addCause(cause)
                .addEvent("source.fetch")
                .addResult("error")
                .addDurationMs(durationMs(startedNs))
                .addErrorKind("INTERNAL")
                .addErrorCode("SOURCE_FETCH_FAILED")
                .log("Source fetch failed"));
    }

    private static long durationMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static void withOperation(String operation, Runnable runnable) {
        String previous = MdcContext.getOperation();
        MdcContext.putOperation(operation);
        try {
            runnable.run();
        } finally {
            MdcContext.restoreOperation(previous);
        }
    }
}
