package riid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Helper to run external processes and capture output with limits.
 */
public final class BoundedCommandExecution {
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            16,
            Thread.ofVirtual().name("cmd-io-", 0).factory());
    private static volatile int MAX_OUTPUT_BYTES = DEFAULT_MAX_OUTPUT_BYTES;

    private BoundedCommandExecution() { }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(EXECUTOR::shutdown));
    }

    public static ShellResult run(List<String> command) throws IOException, InterruptedException {
        return run(command, MAX_OUTPUT_BYTES);
    }

    public static ShellResult run(List<String> command, int maxOutputBytes) throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }

        Process process = new ProcessBuilder(command).start();
        Future<String> stdout = EXECUTOR.submit(streamReader(process.getInputStream(), maxOutputBytes));
        Future<String> stderr = EXECUTOR.submit(streamReader(process.getErrorStream(), maxOutputBytes));
        int exitCode = process.waitFor();
        return new ShellResult(exitCode, get(stdout), get(stderr));
    }

    private static Callable<String> streamReader(InputStream stream, int maxBytes) {
        return () -> {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int total = 0;
                int read = in.read(buffer);
                while (read != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Process output exceeds maxOutputBytes=" + maxBytes);
                    }
                    out.write(buffer, 0, read);
                    read = in.read(buffer);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        };
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

    static String getForTest(Future<String> future) throws IOException, InterruptedException {
        return get(future);
    }

    public static void setMaxOutputBytes(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        MAX_OUTPUT_BYTES = value;
    }

    public record ShellResult(int exitCode, String stdout, String stderr) { }
}

