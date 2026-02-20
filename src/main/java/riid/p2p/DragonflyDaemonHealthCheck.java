package riid.p2p;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import riid.p2p.config.DragonflyConfig;
import riid.p2p.config.DragonflyHealthConfig;
import riid.runtime.BoundedCommandExecution;
import riid.runtime.OutputConfig;

/**
 * Daemon endpoint health check: socket file existence or grpc_health_probe.
 */
final class DragonflyDaemonHealthCheck {

    private static final String UNIX_ADDR_PREFIX = "unix://";

    private DragonflyDaemonHealthCheck() {
    }

    /**
     * Returns true if dfdaemon is reachable. Uses grpc_health_probe when configured,
     * otherwise checks socket file existence.
     */
    static boolean isDaemonReachable(DragonflyConfig config) {
        String endpoint = config.connection().daemonEndpointOrDefault();
        String probePath = config.health().grpcHealthProbePathOrDefault();
        if (probePath != null) {
            return runGrpcHealthProbe(probePath, endpoint, config.health());
        }
        return Files.exists(Path.of(endpoint));
    }

    private static boolean runGrpcHealthProbe(String probePath, String socketPath, DragonflyHealthConfig health) {
        Path absPath = Path.of(socketPath).toAbsolutePath();
        String addr = UNIX_ADDR_PREFIX + absPath.toString();
        List<String> command = new ArrayList<>();
        command.add(probePath);
        command.add("-addr=" + addr);
        long timeoutMs = health.schedulerConnectTimeoutOrDefault().toMillis();
        command.add("-connect-timeout=" + toGrpcDuration(timeoutMs));
        command.add("-rpc-timeout=" + toGrpcDuration(Math.min(timeoutMs, 2000)));
        try {
            Future<BoundedCommandExecution.ShellResult> future =
                    BoundedCommandExecution.run(command, OutputConfig.defaults());
            BoundedCommandExecution.ShellResult result = future.get(
                    timeoutMs + 1000, TimeUnit.MILLISECONDS);
            return result.exitCode() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private static String toGrpcDuration(long millis) {
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return millis + "ms";
    }
}
