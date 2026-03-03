package riid.dispatcher.logging;

/**
 * Emits dispatcher domain events to an underlying sink (e.g. structured logs).
 */
public interface DispatcherEventEmitter {
    void sourceSelected(DispatcherEventContext context, String source, String reason);

    void sourceFetchSuccess(DispatcherEventContext context, String source, long durationMs);

    void sourceFetchMiss(DispatcherEventContext context,
                         String source,
                         long durationMs,
                         DispatcherLogErrorCode errorCode,
                         String errorKind);

    void sourceFetchError(DispatcherEventContext context,
                          String source,
                          long durationMs,
                          DispatcherLogErrorCode errorCode,
                          String errorKind);

    void cachePutWarning(DispatcherEventContext context,
                         String source,
                         DispatcherLogErrorCode errorCode,
                         String errorKind);

    void p2pPublishWarning(DispatcherEventContext context, String errorKind);

    void tempFileDeleteWarning(DispatcherEventContext context,
                               String phase,
                               String path,
                               String errorKind);
}
