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
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    public static final int DEFAULT_STREAM_THREADS = 2;

    private BoundedCommandExecution() { }

    public static ShellResult run(List<String> command) throws IOException, InterruptedException {
        return run(command, DEFAULT_MAX_OUTPUT_BYTES, DEFAULT_STREAM_THREADS);
    }

    public static ShellResult run(List<String> command, int maxOutputBytes) throws IOException, InterruptedException {
        return run(command, maxOutputBytes, DEFAULT_STREAM_THREADS);
    }

    public static ShellResult run(List<String> command, int maxOutputBytes, int streamThreads)
            throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        if (streamThreads <= 0) {
            throw new IllegalArgumentException("streamThreads must be positive");
        }

        Process process = new ProcessBuilder(command).start();
        try (ExecutorService executor = Executors.newFixedThreadPool(streamThreads)) {
            Future<String> stdout = executor.submit(streamReader(process.getInputStream(), maxOutputBytes));
            Future<String> stderr = executor.submit(streamReader(process.getErrorStream(), maxOutputBytes));
            int exitCode = process.waitFor();
            return new ShellResult(exitCode, get(stdout), get(stderr));
        }
    }

    private static Callable<String> streamReader(InputStream stream, int maxBytes) {
        return () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read = stream.read(buffer);
            while (read != -1) {
                int remaining = maxBytes - total;
                if (remaining <= 0) {
                    break;
                }
                int toWrite = Math.min(read, remaining);
                out.write(buffer, 0, toWrite);
                total += toWrite;
                read = stream.read(buffer);
            }
            return out.toString(StandardCharsets.UTF_8);
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

    public record ShellResult(int exitCode, String stdout, String stderr) { }
}

