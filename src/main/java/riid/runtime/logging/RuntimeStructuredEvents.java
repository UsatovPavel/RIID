package riid.runtime.logging;

import java.util.List;

import org.slf4j.Logger;

import riid.core.logging.StructuredLog;

/**
 * Runtime module structured events.
 */
public final class RuntimeStructuredEvents {
    private static final String COMPONENT = "runtime";
    private static final String OPERATION = "command.run";

    private RuntimeStructuredEvents() {
    }

    public static void commandStart(Logger logger, String mode, List<String> command) {
        StructuredLog.info(
                logger,
                "runtime.command",
                COMPONENT,
                OPERATION,
                "start",
                0L,
                null,
                null,
                StructuredLog.fields(
                        "mode", mode,
                        "command_name", commandName(command),
                        "command_args_count", Math.max(command.size() - 1, 0)
                )
        );
    }

    public static void commandSuccess(Logger logger,
                                      String mode,
                                      List<String> command,
                                      long durationMs,
                                      int exitCode) {
        StructuredLog.info(
                logger,
                "runtime.command",
                COMPONENT,
                OPERATION,
                "success",
                durationMs,
                null,
                null,
                StructuredLog.fields(
                        "mode", mode,
                        "command_name", commandName(command),
                        "command_args_count", Math.max(command.size() - 1, 0),
                        "exit_code", exitCode
                )
        );
    }

    public static void commandError(Logger logger,
                                    String mode,
                                    List<String> command,
                                    long durationMs,
                                    RuntimeErrorCode errorCode,
                                    RuntimeErrorKind errorKind) {
        StructuredLog.error(
                logger,
                "runtime.command",
                COMPONENT,
                OPERATION,
                "error",
                durationMs,
                errorCode.name(),
                errorKind.name(),
                StructuredLog.fields(
                        "mode", mode,
                        "command_name", commandName(command),
                        "command_args_count", Math.max(command.size() - 1, 0)
                )
        );
    }

    public static void outputLimitExceeded(Logger logger,
                                           String streamName,
                                           int maxBytes,
                                           String mode,
                                           List<String> command) {
        StructuredLog.warn(
                logger,
                "runtime.output.limit",
                COMPONENT,
                OPERATION,
                "warn",
                0L,
                RuntimeErrorCode.OUTPUT_LIMIT_EXCEEDED.name(),
                RuntimeErrorKind.OUTPUT_LIMIT.name(),
                StructuredLog.fields(
                        "stream", streamName,
                        "max_bytes", maxBytes,
                        "mode", mode,
                        "command_name", commandName(command),
                        "command_args_count", Math.max(command.size() - 1, 0)
                )
        );
    }

    private static String commandName(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "unknown";
        }
        String first = command.getFirst();
        return first == null || first.isBlank() ? "unknown" : first;
    }
}
