package riid.dispatcher.logging;

import org.slf4j.Logger;

/**
 * Structured-log implementation of dispatcher event emitter.
 */
public final class StructuredDispatcherEventEmitter implements DispatcherEventEmitter {
    private final Logger logger;

    public StructuredDispatcherEventEmitter(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void sourceSelected(DispatcherEventContext context, String source, String reason) {
        DispatcherStructuredEvents.sourceSelected(logger, source, reason, context.repository(), context.digest());
    }

    @Override
    public void sourceFetchSuccess(DispatcherEventContext context, String source, long durationMs) {
        DispatcherStructuredEvents.sourceFetchSuccess(logger, source, durationMs, context.repository(), context.digest());
    }

    @Override
    public void sourceFetchMiss(DispatcherEventContext context,
                                String source,
                                long durationMs,
                                DispatcherLogErrorCode errorCode,
                                String errorKind) {
        DispatcherStructuredEvents.sourceFetchMiss(
                logger, source, durationMs, errorCode, errorKind, context.repository(), context.digest());
    }

    @Override
    public void sourceFetchError(DispatcherEventContext context,
                                 String source,
                                 long durationMs,
                                 DispatcherLogErrorCode errorCode,
                                 String errorKind) {
        DispatcherStructuredEvents.sourceFetchError(
                logger, source, durationMs, errorCode, errorKind, context.repository(), context.digest());
    }

    @Override
    public void cachePutWarning(DispatcherEventContext context,
                                String source,
                                DispatcherLogErrorCode errorCode,
                                String errorKind) {
        DispatcherStructuredEvents.cachePutWarning(
                logger,
                source,
                errorCode,
                errorKind,
                context.repository(),
                context.digest(),
                context.mediaType()
        );
    }

    @Override
    public void p2pPublishWarning(DispatcherEventContext context, String errorKind) {
        DispatcherStructuredEvents.p2pPublishWarning(logger, context.repository(), context.digest(), errorKind);
    }

    @Override
    public void tempFileDeleteWarning(DispatcherEventContext context,
                                      String phase,
                                      String path,
                                      String errorKind) {
        DispatcherStructuredEvents.tempFileDeleteWarning(
                logger, phase, path, context.repository(), context.digest(), errorKind);
    }
}
