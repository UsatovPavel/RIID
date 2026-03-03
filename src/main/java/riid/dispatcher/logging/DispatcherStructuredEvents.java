package riid.dispatcher.logging;

import org.slf4j.Logger;

import riid.cache.oci.ImageDigest;
import riid.core.logging.StructuredLog;
import riid.dispatcher.model.RepositoryName;

import java.util.Map;

/**
 * Dispatcher-level structured event helpers.
 */
public final class DispatcherStructuredEvents {
    private static final String COMPONENT = "dispatcher";
    private static final String OPERATION_FETCH_LAYER = "fetchLayer";

    private DispatcherStructuredEvents() {
    }

    public static void sourceSelected(Logger logger,
                                      String source,
                                      String reason,
                                      RepositoryName repository,
                                      ImageDigest digest) {
        if (reason == null || reason.isBlank()) {
            StructuredLog.info(
                    logger,
                    "source.select",
                    COMPONENT,
                    OPERATION_FETCH_LAYER,
                    "chosen",
                    0L,
                    null,
                    null,
                    baseFields(source, repository, digest)
            );
            return;
        }
        StructuredLog.info(
                logger,
                "source.select",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "chosen",
                0L,
                null,
                null,
                StructuredLog.fields(
                        "source", source,
                        "reason", reason,
                        "digest", digest.toString(),
                        "repository", repository.value()
                )
        );
    }

    public static void sourceFetchSuccess(Logger logger,
                                          String source,
                                          long durationMs,
                                          RepositoryName repository,
                                          ImageDigest digest) {
        StructuredLog.info(
                logger,
                "source.fetch",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "success",
                durationMs,
                null,
                null,
                baseFields(source, repository, digest)
        );
    }

    public static void sourceFetchMiss(Logger logger,
                                       String source,
                                       long durationMs,
                                       DispatcherLogErrorCode errorCode,
                                       String errorKind,
                                       RepositoryName repository,
                                       ImageDigest digest) {
        StructuredLog.warn(
                logger,
                "source.fetch",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "miss",
                durationMs,
                errorCode.name(),
                errorKind,
                baseFields(source, repository, digest)
        );
    }

    public static void sourceFetchError(Logger logger,
                                        String source,
                                        long durationMs,
                                        DispatcherLogErrorCode errorCode,
                                        String errorKind,
                                        RepositoryName repository,
                                        ImageDigest digest) {
        StructuredLog.error(
                logger,
                "source.fetch",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "error",
                durationMs,
                errorCode.name(),
                errorKind,
                baseFields(source, repository, digest)
        );
    }

    public static void cachePutWarning(Logger logger,
                                       String source,
                                       DispatcherLogErrorCode errorCode,
                                       String errorKind,
                                       RepositoryName repository,
                                       ImageDigest digest,
                                       String mediaType) {
        StructuredLog.warn(
                logger,
                "cache.put",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "warn",
                0L,
                errorCode.name(),
                errorKind,
                StructuredLog.fields(
                        "source", source,
                        "digest", digest.toString(),
                        "repository", repository.value(),
                        "media_type", mediaType
                )
        );
    }

    public static void p2pPublishWarning(Logger logger,
                                         RepositoryName repository,
                                         ImageDigest digest,
                                         String errorKind) {
        StructuredLog.warn(
                logger,
                "p2p.publish",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "warn",
                0L,
                DispatcherLogErrorCode.P2P_PUBLISH_FAILED.name(),
                errorKind,
                StructuredLog.fields(
                        "digest", digest.toString(),
                        "repository", repository.value()
                )
        );
    }

    public static void tempFileDeleteWarning(Logger logger,
                                             String phase,
                                             String path,
                                             RepositoryName repository,
                                             ImageDigest digest,
                                             String errorKind) {
        StructuredLog.warn(
                logger,
                "temp.delete",
                COMPONENT,
                OPERATION_FETCH_LAYER,
                "warn",
                0L,
                DispatcherLogErrorCode.TEMP_FILE_DELETE_FAILED.name(),
                errorKind,
                StructuredLog.fields(
                        "phase", phase,
                        "path", path,
                        "digest", digest.toString(),
                        "repository", repository.value()
                )
        );
    }

    private static Map<String, Object> baseFields(String source,
                                                  RepositoryName repository,
                                                  ImageDigest digest) {
        return StructuredLog.fields(
                "source", source,
                "digest", digest.toString(),
                "repository", repository.value()
        );
    }
}
