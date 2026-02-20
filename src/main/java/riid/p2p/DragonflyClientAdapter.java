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

import riid.p2p.config.DragonflyConfig;
import riid.p2p.config.DragonflyConnectionConfig;
import riid.p2p.config.DragonflyHealthConfig;
import riid.p2p.config.DragonflyRequestConfig;
import riid.runtime.BoundedCommandExecution;
import riid.runtime.OutputConfig;

/**
 * Boundary adapter for Dragonfly dfget CLI calls.
 */
public final class DragonflyClientAdapter {
    private static final String DEFAULT_DFGET = "dfget";

    private final DragonflyConfig config;

    public DragonflyClientAdapter(DragonflyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void download(String url, Path output, String digest) throws IOException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(digest, "digest");

        DragonflyRequestConfig request = config.request();
        ensureHealthy();
        int attempts = Math.max(1, request.maxRetriesOrDefault() + 1);
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
        DragonflyRequestConfig request = config.request();
        List<String> command = buildDfgetCommand(url, output, digest);
        Future<BoundedCommandExecution.ShellResult> future =
                BoundedCommandExecution.run(command, OutputConfig.defaults());
        Duration timeout = request.requestTimeoutOrDefault();
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
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
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
        DragonflyConnectionConfig connection = config.connection();
        String endpoint = connection.daemonEndpointOrDefault();
        if (!Files.exists(Path.of(endpoint))) {
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.UNHEALTHY,
                    "dragonfly endpoint is unavailable");
        }
        String scheduler = connection.schedulerAddr();
        if (scheduler != null && !scheduler.isBlank() && !schedulerReachable(scheduler, config.health())) {
            throw new DragonflyClientException(
                    DragonflyClientException.ErrorKind.UNHEALTHY,
                    "dragonfly scheduler is unreachable");
        }
    }

    private List<String> buildDfgetCommand(String url, Path output, String digest) {
        DragonflyConnectionConfig connection = config.connection();
        DragonflyRequestConfig request = config.request();
        List<String> cmd = new ArrayList<>();
        String bin = connection.dfgetPath() != null && !connection.dfgetPath().isBlank()
                ? connection.dfgetPath()
                : DEFAULT_DFGET;
        cmd.add(bin);
        cmd.add("-e");
        cmd.add(connection.daemonEndpointOrDefault());
        cmd.add("-O");
        cmd.add(output.toAbsolutePath().toString());
        cmd.add(url);
        cmd.add("--content-for-calculating-task-id");
        cmd.add(digest);
        cmd.add("--digest");
        cmd.add(digest);
        if (request.tag() != null && !request.tag().isBlank()) {
            cmd.add("--tag");
            cmd.add(request.tag());
        }
        if (request.application() != null && !request.application().isBlank()) {
            cmd.add("--application");
            cmd.add(request.application());
        }
        for (String header : request.headersOrEmpty()) {
            cmd.add("-H");
            cmd.add(header);
        }
        cmd.add("--timeout");
        cmd.add(toCliDuration(request.requestTimeoutOrDefault()));
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

    private static boolean schedulerReachable(String scheduler, DragonflyHealthConfig health) {
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
                    Math.toIntExact(health.schedulerConnectTimeoutOrDefault().toMillis()));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

}
