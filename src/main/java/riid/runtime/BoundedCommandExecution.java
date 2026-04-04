package riid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    /**
     * Runs {@code producer} and {@code consumer} with producer stdout connected to consumer stdin.
     * Both stderr streams are read up to {@code maxStderrBytes} each (truncated after the limit).
     * Uses the shared executor for stream I/O (same as {@link #run(List, OutputConfig)}).
     *
     * @param maxStderrBytes per-process stderr cap; if {@code <= 0}, {@link #DEFAULT_MAX_OUTPUT_BYTES} is used
     */
    public static PipedShellResult runWithStdoutPipedToStdin(
            List<String> producerCommand,
            List<String> consumerCommand,
            int maxStderrBytes,
            ProcessStarter starter) throws IOException, InterruptedException {
        Objects.requireNonNull(producerCommand, "producerCommand");
        Objects.requireNonNull(consumerCommand, "consumerCommand");
        Objects.requireNonNull(starter, "starter");
        int stderrLimit = maxStderrBytes > 0 ? maxStderrBytes : DEFAULT_MAX_OUTPUT_BYTES;
        Process producer = starter.start(producerCommand);
        Process consumer = starter.start(consumerCommand);
        ExecutorService exec = EXECUTOR_REF.get();
        try {
            Future<String> producerStderr = exec.submit(
                    streamReaderTruncating(producer.getErrorStream(), stderrLimit, "piped-producer-stderr", true));
            Future<String> consumerStderr = exec.submit(
                    streamReaderTruncating(consumer.getErrorStream(), stderrLimit, "piped-consumer-stderr", true));
            Future<Void> pipeTransfer = exec.submit(() -> {
                try (InputStream in = producer.getInputStream(); OutputStream out = consumer.getOutputStream()) {
                    in.transferTo(out);
                }
                return null;
            });
            try {
                pipeTransfer.get();
            } catch (ExecutionException e) {
                rethrowPipeTransferFailure(e);
            }
            int producerExit = producer.waitFor();
            int consumerExit = consumer.waitFor();
            return new PipedShellResult(producerExit, consumerExit, get(producerStderr), get(consumerStderr));
        } finally {
            if (producer.isAlive()) {
                producer.destroyForcibly();
            }
            if (consumer.isAlive()) {
                consumer.destroyForcibly();
            }
        }
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

    private static void rethrowPipeTransferFailure(ExecutionException e) throws IOException, InterruptedException {
        Throwable c = e.getCause();
        if (c instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
        if (c instanceof IOException io) {
            throw io;
        }
        if (c instanceof Error err) {
            throw err;
        }
        if (c instanceof RuntimeException re) {
            throw re;
        }
        throw new IOException(c);
    }

    public static CompletableFuture<ShellResult> run(List<String> command, int maxOutputBytes) {
        Objects.requireNonNull(command, "command");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        ExecutorService localExecutor = EXECUTOR_REF.get();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(command).start();
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
            try {
                Process process = new ProcessBuilder(command).start();
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
                throw new RuntimeException(e);
            }
        }, localExecutor);
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

