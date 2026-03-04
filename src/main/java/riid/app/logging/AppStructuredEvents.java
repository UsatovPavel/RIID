package riid.app.logging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;

import riid.core.logging.StructuredLog;

/**
 * App-level structured event helpers.
 */
public final class AppStructuredEvents {
    private static final String COMPONENT_APP = "app";
    private static final String COMPONENT_RUNTIME = "runtime";
    private static final String MILESTONE_TYPE_PERFORMANCE = "performance";

    private AppStructuredEvents() {
    }

    public static void requestStart(Logger logger, int argsCount) {
        StructuredLog.info(
                logger,
                "request.start",
                COMPONENT_APP,
                "cli.run",
                "start",
                0L,
                null,
                null,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "args_count", argsCount
                )
        );
    }

    public static void requestFinishSuccess(Logger logger, long durationMs, int exitCode) {
        StructuredLog.info(
                logger,
                "request.finish",
                COMPONENT_APP,
                "cli.run",
                "success",
                durationMs,
                null,
                null,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "exit_code", exitCode
                )
        );
    }

    public static void requestFinishError(Logger logger,
                                          long durationMs,
                                          int exitCode,
                                          AppLogErrorCode errorCode,
                                          String errorKind) {
        StructuredLog.error(
                logger,
                "request.finish",
                COMPONENT_APP,
                "cli.run",
                "error",
                durationMs,
                errorCode.name(),
                errorKind,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "exit_code", exitCode
                )
        );
    }

    public static void configResolveSuccess(Logger logger,
                                            long durationMs,
                                            String configSource,
                                            String configPath) {
        StructuredLog.info(
                logger,
                "config.resolve",
                COMPONENT_APP,
                "serviceFactory",
                "success",
                durationMs,
                null,
                null,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "config_source", configSource,
                        "config_path", configPath
                )
        );
    }

    public static void configResolveError(Logger logger,
                                          long durationMs,
                                          String configSource,
                                          String configPath,
                                          AppLogErrorCode errorCode,
                                          String errorKind) {
        StructuredLog.error(
                logger,
                "config.resolve",
                COMPONENT_APP,
                "serviceFactory",
                "error",
                durationMs,
                errorCode.name(),
                errorKind,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "config_source", configSource,
                        "config_path", configPath
                )
        );
    }

    public static void manifestFetchSuccess(Logger logger,
                                            long durationMs,
                                            String repository,
                                            String reference) {
        StructuredLog.info(
                logger,
                "manifest.fetch",
                COMPONENT_APP,
                "load",
                "success",
                durationMs,
                null,
                null,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "repository", repository,
                        "reference", reference
                )
        );
    }

    public static void manifestFetchError(Logger logger,
                                          long durationMs,
                                          String repository,
                                          String reference,
                                          AppLogErrorCode errorCode,
                                          String errorKind) {
        StructuredLog.error(
                logger,
                "manifest.fetch",
                COMPONENT_APP,
                "load",
                "error",
                durationMs,
                errorCode.name(),
                errorKind,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "repository", repository,
                        "reference", reference
                )
        );
    }

    public static void engineImportSuccess(Logger logger,
                                           long durationMs,
                                           String runtimeId,
                                           String archivePath) {
        StructuredLog.info(
                logger,
                "engine.import",
                COMPONENT_RUNTIME,
                "import",
                "success",
                durationMs,
                null,
                null,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "runtime_id", runtimeId,
                        "archive_path", archivePath
                )
        );
    }

    public static void engineImportError(Logger logger,
                                         long durationMs,
                                         String runtimeId,
                                         AppLogErrorCode errorCode,
                                         String errorKind) {
        StructuredLog.error(
                logger,
                "engine.import",
                COMPONENT_RUNTIME,
                "import",
                "error",
                durationMs,
                errorCode.name(),
                errorKind,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "runtime_id", runtimeId
                )
        );
    }

    public static void archiveBuildSuccess(Logger logger,
                                           long durationMs,
                                           String repository,
                                           String reference) {
        StructuredLog.info(
                logger,
                "archive.build",
                COMPONENT_APP,
                "oci.archive.build",
                "success",
                durationMs,
                null,
                null,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "repository", repository,
                        "reference", reference
                )
        );
    }

    public static void archiveBuildError(Logger logger,
                                         long durationMs,
                                         String repository,
                                         String reference,
                                         AppLogErrorCode errorCode,
                                         String errorKind) {
        StructuredLog.error(
                logger,
                "archive.build",
                COMPONENT_APP,
                "oci.archive.build",
                "error",
                durationMs,
                errorCode.name(),
                errorKind,
                milestoneFields(
                        MILESTONE_TYPE_PERFORMANCE,
                        "repository", repository,
                        "reference", reference
                )
        );
    }

    private static Map<String, Object> milestoneFields(String milestoneType, Object... extraFields) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("milestone", true);
        fields.put("milestone_type", milestoneType);
        if (extraFields != null && extraFields.length > 0) {
            fields.putAll(StructuredLog.fields(extraFields));
        }
        return Map.copyOf(fields);
    }
}
