package riid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.core.logging.LogSecretsRemover;
import riid.runtime.logging.RuntimeErrorCode;
import riid.runtime.logging.RuntimeErrorKind;
import riid.runtime.logging.RuntimeStructuredEvents;

/**
 * Helper to run external processes and capture output with limits.
 */
public final class BoundedCommandExecution {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedCommandExecution.class);
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_TASKS_COMMAND_EXECUTOR = 16;
    private static final int BUFFER_SIZE = 4096;
    private static final AtomicReference<ExecutorService> EXECUTOR_REF =
            new AtomicReference<>(newExecutor(DEFAULT_MAX_TASKS_COMMAND_EXECUTOR));
    private static volatile OutputConfig DEFAULT_OUTPUT_CONFIG = OutputConfig.defaults();

    private BoundedCommandExecution() { }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BoundedCommandExecution::shutdownExecutor));
    }

    public static ShellResult run(List<String> command) throws IOException, InterruptedException {
        return get(run(command, DEFAULT_OUTPUT_CONFIG));
    }

    public static CompletableFuture<ShellResult> run(List<String> command, int maxOutputBytes) {
        Objects.requireNonNull(command, "command");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        ExecutorService localExecutor = EXECUTOR_REF.get();
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            List<String> redactedCommand = redactCommandForLogging(command);
            RuntimeStructuredEvents.commandStart(LOGGER, "strict", redactedCommand);
            try {
                Process process = new ProcessBuilder(command).start();
                Future<String> stdout = localExecutor.submit(streamReaderStrict(process.getInputStream(),
                        maxOutputBytes, "stdout", redactedCommand));
                Future<String> stderr = localExecutor.submit(streamReaderStrict(process.getErrorStream(),
                        maxOutputBytes, "stderr", redactedCommand));
                int exitCode = process.waitFor();
                ShellResult result = new ShellResult(exitCode, get(stdout), get(stderr));
                if (result.exitCode() == 0) {
                    RuntimeStructuredEvents.commandSuccess(
                            LOGGER, "strict", redactedCommand, elapsedMs(started), result.exitCode());
                } else {
                    RuntimeStructuredEvents.commandError(
                            LOGGER,
                            "strict",
                            redactedCommand,
                            elapsedMs(started),
                            RuntimeErrorCode.PROCESS_EXIT_NON_ZERO,
                            RuntimeErrorKind.NON_ZERO_EXIT
                    );
                }
                return result;
            } catch (OutputLimitExceededException e) {
                RuntimeStructuredEvents.outputLimitExceeded(
                        LOGGER, "unknown", maxOutputBytes, "strict", redactedCommand);
                RuntimeStructuredEvents.commandError(
                        LOGGER,
                        "strict",
                        redactedCommand,
                        elapsedMs(started),
                        RuntimeErrorCode.OUTPUT_LIMIT_EXCEEDED,
                        RuntimeErrorKind.OUTPUT_LIMIT
                );
                throw new RuntimeException(e);
            } catch (IOException e) {
                RuntimeStructuredEvents.commandError(
                        LOGGER,
                        "strict",
                        redactedCommand,
                        elapsedMs(started),
                        RuntimeErrorCode.PROCESS_IO_ERROR,
                        RuntimeErrorKind.IO
                );
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RuntimeStructuredEvents.commandError(
                        LOGGER,
                        "strict",
                        redactedCommand,
                        elapsedMs(started),
                        RuntimeErrorCode.PROCESS_INTERRUPTED,
                        RuntimeErrorKind.INTERRUPTED
                );
                throw new RuntimeException(e);
            }
        }, localExecutor);
    }

    public static CompletableFuture<ShellResult> run(List<String> command, OutputConfig outputConfig) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outputConfig, "outputConfig");
        outputConfig.validate();

        ExecutorService localExecutor = EXECUTOR_REF.get();
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            List<String> redactedCommand = redactCommandForLogging(command);
            RuntimeStructuredEvents.commandStart(LOGGER, "truncating", redactedCommand);
            try {
                Process process = new ProcessBuilder(command).start();
                Future<String> stdout = localExecutor.submit(streamReaderTruncating(process.getInputStream(),
                        outputConfig.maxStdoutBytes(), "stdout", outputConfig.captureStdout(), redactedCommand));
                Future<String> stderr = localExecutor.submit(streamReaderTruncating(process.getErrorStream(),
                        outputConfig.maxStderrBytes(), "stderr", outputConfig.captureStderr(), redactedCommand));
                int exitCode = process.waitFor();
                ShellResult result = new ShellResult(exitCode, get(stdout), get(stderr));
                if (result.exitCode() == 0) {
                    RuntimeStructuredEvents.commandSuccess(
                            LOGGER, "truncating", redactedCommand, elapsedMs(started), result.exitCode());
                } else {
                    RuntimeStructuredEvents.commandError(
                            LOGGER,
                            "truncating",
                            redactedCommand,
                            elapsedMs(started),
                            RuntimeErrorCode.PROCESS_EXIT_NON_ZERO,
                            RuntimeErrorKind.NON_ZERO_EXIT
                    );
                }
                return result;
            } catch (IOException e) {
                RuntimeStructuredEvents.commandError(
                        LOGGER,
                        "truncating",
                        redactedCommand,
                        elapsedMs(started),
                        RuntimeErrorCode.PROCESS_IO_ERROR,
                        RuntimeErrorKind.IO
                );
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RuntimeStructuredEvents.commandError(
                        LOGGER,
                        "truncating",
                        redactedCommand,
                        elapsedMs(started),
                        RuntimeErrorCode.PROCESS_INTERRUPTED,
                        RuntimeErrorKind.INTERRUPTED
                );
                throw new RuntimeException(e);
            }
        }, localExecutor);
    }

    private static Callable<String> streamReaderStrict(InputStream stream,
                                                       int maxBytes,
                                                       String name,
                                                       List<String> command) {
        return () -> {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int total = 0;
                int read = in.read(buffer);
                while (read != -1) {
                    total += read;
                    if (total > maxBytes) {
                        RuntimeStructuredEvents.outputLimitExceeded(
                                LOGGER, name, maxBytes, "strict", command);
                        throw new OutputLimitExceededException(
                                "Process " + name + " output exceeds maxOutputBytes=" + maxBytes);
                    }
                    out.write(buffer, 0, read);
                    read = in.read(buffer);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        };
    }

    private static Callable<String> streamReaderTruncating(InputStream stream,
                                                           Integer maxBytes,
                                                           String name,
                                                           boolean capture,
                                                           List<String> command) {
        return () -> {
            try (InputStream in = stream) {
                if (!capture) {
                    drain(in);
                    return "";
                }
                int limit = maxBytes != null ? maxBytes : DEFAULT_MAX_OUTPUT_BYTES;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int total = 0;
                int read = in.read(buffer);
                while (read != -1) {
                    int nextTotal = total + read;
                    if (nextTotal > limit) {
                        int allowed = limit - total;
                        if (allowed > 0) {
                            out.write(buffer, 0, allowed);
                        }
                        RuntimeStructuredEvents.outputLimitExceeded(
                                LOGGER, name, limit, "truncating", command);
                        drain(in);
                        break;
                    }
                    out.write(buffer, 0, read);
                    total = nextTotal;
                    read = in.read(buffer);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        };
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (in.read(buffer) != -1) {
            // discard
        }
    }

    private static String get(Future<String> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException ie) {
                throw ie;
            }
            throw new IOException("Failed to read process output", cause);
        }
    }

    private static ShellResult get(CompletableFuture<ShellResult> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re && re.getCause() != null) {
                cause = re.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException ie) {
                throw ie;
            }
            throw new IOException("Failed to run process", cause);
        }
    }

    static String getForTest(Future<String> future) throws IOException, InterruptedException {
        return get(future);
    }

    public static List<String> redactCommandForLogging(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        List<String> redacted = new ArrayList<>(command.size());
        for (String part : command) {
            redacted.add(LogSecretsRemover.sanitizeText(part));
        }
        return List.copyOf(redacted);
    }

    public static void setMaxOutputBytes(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        DEFAULT_OUTPUT_CONFIG = DEFAULT_OUTPUT_CONFIG.withMaxOutputBytes(value);
    }

    public static void setDefaultOutputConfig(OutputConfig outputConfig) {
        Objects.requireNonNull(outputConfig, "outputConfig");
        outputConfig.validate();
        DEFAULT_OUTPUT_CONFIG = outputConfig;
    }

    public static synchronized void setMaxTasksCommandExecutor(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxTasksCommandExecutor must be positive");
        }
        ExecutorService oldExecutor = EXECUTOR_REF.getAndSet(newExecutor(value));
        oldExecutor.shutdown();
    }

    private static ExecutorService newExecutor(int maxTasks) {
        return Executors.newFixedThreadPool(
                maxTasks,
                Thread.ofVirtual().name("cmd-io-", 0).factory());
    }

    private static void shutdownExecutor() {
        EXECUTOR_REF.get().shutdown();
    }

    public record ShellResult(int exitCode, String stdout, String stderr) { }

    public static final class OutputLimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        public OutputLimitExceededException(String message) {
            super(message);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}

