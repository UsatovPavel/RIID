package riid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Helper to run external processes and capture output with limits.
 */
public final class BoundedCommandExecution {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedCommandExecution.class);
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_TASKS_COMMAND_EXECUTOR = 16;
    private static final int BUFFER_SIZE = 4096;
    private static final long PROCESS_TERMINATION_TIMEOUT_MS = 200;
    private static final AtomicReference<ExecutorService> EXECUTOR_REF =
            new AtomicReference<>(newExecutor(DEFAULT_MAX_TASKS_COMMAND_EXECUTOR));
    private static final BoundedPipedCommandExecutor PIPED_EXECUTOR = new BoundedPipedCommandExecutor(
            DEFAULT_MAX_OUTPUT_BYTES);
    private static volatile OutputConfig DEFAULT_OUTPUT_CONFIG = OutputConfig.defaults();

    private BoundedCommandExecution() { }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(BoundedCommandExecution::shutdownExecutor));
    }

    public static ShellResult run(List<String> command) throws IOException, InterruptedException {
        return get(run(command, DEFAULT_OUTPUT_CONFIG));
    }

    public static PipedShellResult runWithStdoutPipedToStdin(
            List<String> producerCommand,
            List<String> consumerCommand,
            int maxStderrBytes,
            ProcessStarter starter) throws IOException, InterruptedException {
        return PIPED_EXECUTOR.runWithStdoutPipedToStdin(producerCommand, consumerCommand, maxStderrBytes, starter);
    }

    /**
     * Same as {@link #runWithStdoutPipedToStdin(List, List, int, ProcessStarter)} with
     * {@code cmd -> new ProcessBuilder(cmd).start()}.
     */
    public static PipedShellResult runWithStdoutPipedToStdin(
            List<String> producerCommand,
            List<String> consumerCommand,
            int maxStderrBytes) throws IOException, InterruptedException {
        return runWithStdoutPipedToStdin(
                producerCommand, consumerCommand, maxStderrBytes, cmd -> new ProcessBuilder(cmd).start());
    }

    public static CompletableFuture<ShellResult> run(List<String> command, int maxOutputBytes) {
        Objects.requireNonNull(command, "command");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        ExecutorService localExecutor = EXECUTOR_REF.get();
        AtomicReference<Process> processRef = new AtomicReference<>();
        CompletableFuture<ShellResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(command).start();
                processRef.set(process);
                Future<String> stdout = localExecutor.submit(streamReaderStrict(process.getInputStream(),
                        maxOutputBytes, "stdout"));
                Future<String> stderr = localExecutor.submit(streamReaderStrict(process.getErrorStream(),
                        maxOutputBytes, "stderr"));
                int exitCode = process.waitFor();
                return new ShellResult(exitCode, get(stdout), get(stderr));
            } catch (OutputLimitExceededException e) {
                LOGGER.warn("Process output exceeded maxOutputBytes={} for command {}",
                        maxOutputBytes, command, e);
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyProcess(processRef.get(), command);
                throw new RuntimeException(e);
            }
        }, localExecutor);
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                destroyProcess(processRef.get(), command);
            }
        });
        return future;
    }

    public static CompletableFuture<ShellResult> run(List<String> command, OutputConfig outputConfig) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outputConfig, "outputConfig");
        outputConfig.validate();

        ExecutorService localExecutor = EXECUTOR_REF.get();
        AtomicReference<Process> processRef = new AtomicReference<>();
        CompletableFuture<ShellResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(command).start();
                processRef.set(process);
                Future<String> stdout = localExecutor.submit(streamReaderTruncating(process.getInputStream(),
                        outputConfig.maxStdoutBytes(), "stdout", outputConfig.captureStdout()));
                Future<String> stderr = localExecutor.submit(streamReaderTruncating(process.getErrorStream(),
                        outputConfig.maxStderrBytes(), "stderr", outputConfig.captureStderr()));
                int exitCode = process.waitFor();
                return new ShellResult(exitCode, get(stdout), get(stderr));
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyProcess(processRef.get(), command);
                throw new RuntimeException(e);
            }
        }, localExecutor);
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                destroyProcess(processRef.get(), command);
            }
        });
        return future;
    }

    private static void destroyProcess(Process process, List<String> command) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(PROCESS_TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Forcefully destroying child process for command {}", command);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static Callable<String> streamReaderStrict(InputStream stream, int maxBytes, String name) {
        return () -> {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int total = 0;
                int read = in.read(buffer);
                while (read != -1) {
                    total += read;
                    if (total > maxBytes) {
                        LOGGER.warn("Process {} output exceeds maxOutputBytes={}", name, maxBytes);
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
                                                           boolean capture) {
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
                        LOGGER.warn("Process {} output exceeds maxOutputBytes={}, truncating", name, limit);
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
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
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
        PIPED_EXECUTOR.shutdown();
    }

    public record ShellResult(int exitCode, String stdout, String stderr) { }

    /** Result of {@link #runWithStdoutPipedToStdin(List, List, int, ProcessStarter)}. */
    public record PipedShellResult(
            int producerExitCode,
            int consumerExitCode,
            String producerStderr,
            String consumerStderr) {

        public void throwIfFailed(String producerLabel, String consumerLabel) throws IOException {
            if (producerExitCode != 0) {
                throw new IOException(producerLabel + " failed (exit " + producerExitCode + "): " + producerStderr);
            }
            if (consumerExitCode != 0) {
                throw new IOException(consumerLabel + " failed (exit " + consumerExitCode + "): " + consumerStderr);
            }
        }
    }

    @FunctionalInterface
    public interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    public static final class OutputLimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        public OutputLimitExceededException(String message) {
            super(message);
        }
    }
}

