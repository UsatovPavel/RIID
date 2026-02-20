package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import riid.p2p.config.DragonflyConfig;
import riid.p2p.config.DragonflyConnectionConfig;
import riid.p2p.config.DragonflyRequestConfig;
import riid.runtime.BoundedCommandExecution;
import riid.runtime.OutputConfig;

/**
 * Adapter for Dragonfly dfcache CLI (stat, export, import).
 */
public final class DfcacheAdapter {
    private final DragonflyConfig config;

    public DfcacheAdapter(DragonflyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public String taskIdForDigest(String digest) {
        return sha256Hex(digest);
    }

    public boolean tryExportPersistentCache(String taskId, Path output) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(output, "output");
        if (!persistentCacheEnabled()) {
            return false;
        }
        DragonflyConnectionConfig connection = config.connection();
        List<String> statCmd = List.of(
                connection.dfcachePathOrDefault(),
                "stat",
                "-e",
                connection.daemonEndpointOrDefault(),
                taskId);
        BoundedCommandExecution.ShellResult statResult = runCli(statCmd, config.request().requestTimeoutOrDefault());
        if (statResult.exitCode() != 0) {
            return false;
        }
        List<String> exportCmd = List.of(
                connection.dfcachePathOrDefault(),
                "export",
                "-e",
                connection.daemonEndpointOrDefault(),
                "--overwrite",
                "-O",
                output.toAbsolutePath().toString(),
                taskId);
        BoundedCommandExecution.ShellResult exportResult = runCli(exportCmd, config.request().requestTimeoutOrDefault());
        return exportResult.exitCode() == 0;
    }

    public boolean importIntoPersistentCache(String taskKey, Path sourcePath) throws IOException {
        Objects.requireNonNull(taskKey, "taskKey");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (!persistentCacheEnabled()) {
            return false;
        }
        DragonflyConnectionConfig connection = config.connection();
        List<String> importCmd = List.of(
                connection.dfcachePathOrDefault(),
                "import",
                "--no-progress",
                "-e",
                connection.daemonEndpointOrDefault(),
                "--content-for-calculating-task-id",
                taskKey,
                sourcePath.toAbsolutePath().toString());
        BoundedCommandExecution.ShellResult result = runCli(importCmd, config.request().requestTimeoutOrDefault());
        if (result.exitCode() == 0) {
            return true;
        }
        String mergedOutput = (result.stdout() == null ? "" : result.stdout())
                + "\n"
                + (result.stderr() == null ? "" : result.stderr());
        if (mergedOutput.contains("AlreadyExists")) {
            return true;
        }
        return false;
    }

    private boolean persistentCacheEnabled() {
        return config.persistentCache().enabledOrDefault();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private BoundedCommandExecution.ShellResult runCli(List<String> command, Duration timeout) throws IOException {
        Future<BoundedCommandExecution.ShellResult> future =
                BoundedCommandExecution.run(command, OutputConfig.defaults());
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.TIMEOUT,
                    "command timed out after " + timeout.toSeconds() + "s",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.TIMEOUT,
                    "command interrupted",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
            if (cause instanceof IOException io) {
                throw new DragonflyClientException(
                        DragonflyClientException.ErrorKind.IO,
                        "command I/O failure",
                        io);
            }
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.PROCESS_FAILED,
                    "command execution failure",
                    cause);
        }
    }
}
