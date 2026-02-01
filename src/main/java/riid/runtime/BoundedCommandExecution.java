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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to run external processes and capture output with limits.
 */
public final class BoundedCommandExecution {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedCommandExecution.class);
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 4096;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            16,
            Thread.ofVirtual().name("cmd-io-", 0).factory());
    private static volatile OutputConfig DEFAULT_OUTPUT_CONFIG = OutputConfig.defaults();

    private BoundedCommandExecution() { }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(EXECUTOR::shutdown));
    }

    public static ShellResult run(List<String> command) throws IOException, InterruptedException {
        return get(run(command, DEFAULT_OUTPUT_CONFIG));
    }

    public static CompletableFuture<ShellResult> run(List<String> command, int maxOutputBytes) {
        Objects.requireNonNull(command, "command");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(command).start();
                Future<String> stdout = EXECUTOR.submit(streamReaderStrict(process.getInputStream(),
                        maxOutputBytes, "stdout"));
                Future<String> stderr = EXECUTOR.submit(streamReaderStrict(process.getErrorStream(),
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
        }, EXECUTOR);
    }

    public static CompletableFuture<ShellResult> run(List<String> command, OutputConfig outputConfig) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outputConfig, "outputConfig");
        outputConfig.validate();

        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(command).start();
                Future<String> stdout = EXECUTOR.submit(streamReaderTruncating(process.getInputStream(),
                        outputConfig.maxStdoutBytes(), "stdout", outputConfig.captureStdout()));
                Future<String> stderr = EXECUTOR.submit(streamReaderTruncating(process.getErrorStream(),
                        outputConfig.maxStderrBytes(), "stderr", outputConfig.captureStderr()));
                int exitCode = process.waitFor();
                return new ShellResult(exitCode, get(stdout), get(stderr));
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }, EXECUTOR);
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

    public record ShellResult(int exitCode, String stdout, String stderr) { }

    public static final class OutputLimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        public OutputLimitExceededException(String message) {
            super(message);
        }
    }
}

