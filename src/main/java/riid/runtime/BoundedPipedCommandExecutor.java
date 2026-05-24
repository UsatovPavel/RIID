package riid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated executor for piped producer/consumer process operations.
 */
final class BoundedPipedCommandExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedPipedCommandExecutor.class);
    private static final int BUFFER_SIZE = 4096;
    private static final int MAX_CONCURRENT_PIPE_OPERATIONS = 5;
    private static final int PIPE_TASKS_PER_OPERATION = 3;
    private static final int MAX_PIPE_EXECUTOR_TASKS = MAX_CONCURRENT_PIPE_OPERATIONS * PIPE_TASKS_PER_OPERATION;

    private final int defaultMaxOutputBytes;
    private final Semaphore concurrentPipesLimiter = new Semaphore(MAX_CONCURRENT_PIPE_OPERATIONS, true);
    private final ExecutorService pipeIoExecutor = Executors.newFixedThreadPool(MAX_PIPE_EXECUTOR_TASKS,
            Thread.ofVirtual().name("cmd-pipe-io-", 0).factory());

    BoundedPipedCommandExecutor(int defaultMaxOutputBytes) {
        this.defaultMaxOutputBytes = defaultMaxOutputBytes;
    }

    BoundedCommandExecution.PipedShellResult runWithStdoutPipedToStdin(List<String> producerCommand,
            List<String> consumerCommand, int maxStderrBytes, BoundedCommandExecution.ProcessStarter starter)
            throws IOException, InterruptedException {
        Objects.requireNonNull(producerCommand, "producerCommand");
        Objects.requireNonNull(consumerCommand, "consumerCommand");
        Objects.requireNonNull(starter, "starter");

        concurrentPipesLimiter.acquire();
        try {
            int stderrLimit = maxStderrBytes > 0 ? maxStderrBytes : defaultMaxOutputBytes;
            Process producer = starter.start(producerCommand);
            Process consumer;
            try {
                consumer = starter.start(consumerCommand);
            } catch (IOException | RuntimeException e) {
                if (producer.isAlive()) {
                    producer.destroyForcibly();
                }
                throw e;
            }
            try {
                // Submit transfer first: without it producer/consumer can mutually block on
                // pipe backpressure.
                Future<Void> pipeTransfer = pipeIoExecutor.submit(() -> {
                    try (InputStream in = producer.getInputStream(); OutputStream out = consumer.getOutputStream()) {
                        in.transferTo(out);
                    }
                    return null;
                });
                Future<String> producerStderr = pipeIoExecutor.submit(
                        streamReaderTruncating(producer.getErrorStream(), stderrLimit, "piped-producer-stderr"));
                Future<String> consumerStderr = pipeIoExecutor.submit(
                        streamReaderTruncating(consumer.getErrorStream(), stderrLimit, "piped-consumer-stderr"));

                try {
                    pipeTransfer.get();
                } catch (ExecutionException e) {
                    rethrowPipeTransferFailure(e);
                }
                int producerExit = producer.waitFor();
                int consumerExit = consumer.waitFor();
                return new BoundedCommandExecution.PipedShellResult(producerExit, consumerExit, get(producerStderr),
                        get(consumerStderr));
            } finally {
                if (producer.isAlive()) {
                    producer.destroyForcibly();
                }
                if (consumer.isAlive()) {
                    consumer.destroyForcibly();
                }
            }
        } finally {
            concurrentPipesLimiter.release();
        }
    }

    void shutdown() {
        pipeIoExecutor.shutdown();
    }

    private Callable<String> streamReaderTruncating(InputStream stream, int maxBytes, String name) {
        return () -> {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int total = 0;
                int read = in.read(buffer);
                while (read != -1) {
                    int nextTotal = total + read;
                    if (nextTotal > maxBytes) {
                        int allowed = maxBytes - total;
                        if (allowed > 0) {
                            out.write(buffer, 0, allowed);
                        }
                        LOGGER.warn("Process {} output exceeds maxOutputBytes={}, truncating", name, maxBytes);
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
}
