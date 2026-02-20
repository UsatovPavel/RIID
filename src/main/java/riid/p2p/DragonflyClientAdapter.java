package riid.p2p;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import riid.runtime.BoundedCommandExecution;
import riid.runtime.OutputConfig;

/**
 * Boundary adapter for Dragonfly CLI calls.
 */
public final class DragonflyClientAdapter {
    private static final String DEFAULT_DFGET = "dfget";
    private static final String DEFAULT_ENDPOINT = "/tmp/dfdaemon.sock";
    private static final Duration HEALTHCHECK_TIMEOUT = Duration.ofSeconds(1);

    private final DragonflyConfig config;

    public DragonflyClientAdapter(DragonflyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void download(String url, Path output, String digest) throws IOException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(digest, "digest");

        ensureHealthy();
        int attempts = Math.max(1, config.maxRetriesOrDefault() + 1);
        DragonflyClientException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                runDfget(url, output, digest);
                return;
            } catch (DragonflyClientException ex) {
                lastFailure = ex;
                if (!isRecoverable(ex) || attempt == attempts) {
                    throw ex;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    private void runDfget(String url, Path output, String digest) throws IOException {
        List<String> command = buildDfgetCommand(url, output, digest);
        Future<BoundedCommandExecution.ShellResult> future =
                BoundedCommandExecution.run(command, OutputConfig.defaults());
        Duration timeout = config.requestTimeoutOrDefault();
        try {
            BoundedCommandExecution.ShellResult result = future.get(
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result.exitCode() != 0) {
                throw new DragonflyClientException(
                        DragonflyClientException.ErrorKind.PROCESS_FAILED,
                        "dfget failed with non-zero exit code " + result.exitCode());
            }
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.TIMEOUT,
                    "dfget timed out after " + timeout.toSeconds() + "s",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.TIMEOUT,
                    "dfget interrupted",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof IOException io) {
                throw new DragonflyClientException(
                        DragonflyClientException.ErrorKind.IO,
                        "dfget I/O failure",
                        io);
            }
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.PROCESS_FAILED,
                    "dfget execution failure",
                    cause);
        }
    }

    private void ensureHealthy() throws DragonflyClientException {
        String endpoint = config.daemonEndpointOrDefault();
        if (!Files.exists(Path.of(endpoint))) {
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.UNHEALTHY,
                    "dragonfly endpoint is unavailable");
        }
        String scheduler = config.schedulerAddr();
        if (scheduler != null && !scheduler.isBlank() && !schedulerReachable(scheduler)) {
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.UNHEALTHY,
                    "dragonfly scheduler is unreachable");
        }
    }

    private List<String> buildDfgetCommand(String url, Path output, String digest) {
        List<String> cmd = new ArrayList<>();
        String bin = config.dfgetPath() != null && !config.dfgetPath().isBlank()
                ? config.dfgetPath()
                : DEFAULT_DFGET;
        cmd.add(bin);
        cmd.add("-e");
        cmd.add(config.daemonEndpointOrDefault());
        cmd.add("-O");
        cmd.add(output.toAbsolutePath().toString());
        cmd.add(url);
        cmd.add("--digest");
        cmd.add(digest);
        cmd.add("--timeout");
        cmd.add(toCliDuration(config.requestTimeoutOrDefault()));
        cmd.add("--console");
        return cmd;
    }

    private static String toCliDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return millis + "ms";
    }

    private static boolean isRecoverable(DragonflyClientException exception) {
        return exception.kind() == DragonflyClientException.ErrorKind.UNHEALTHY
                || exception.kind() == DragonflyClientException.ErrorKind.TIMEOUT
                || exception.kind() == DragonflyClientException.ErrorKind.PROCESS_FAILED;
    }

    private static boolean schedulerReachable(String scheduler) {
        String[] hostPort = scheduler.split(":", 2);
        if (hostPort.length != 2) {
            return false;
        }
        String host = hostPort[0];
        int port;
        try {
            port = Integer.parseInt(hostPort[1]);
        } catch (NumberFormatException ex) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port),
                    Math.toIntExact(HEALTHCHECK_TIMEOUT.toMillis()));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
