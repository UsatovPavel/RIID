package riid.app.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

import riid.app.cli.CliApplication;
import riid.app.core.config.AppConfig;
import riid.app.daemon.guard.PullConcurrencyGuard;
import riid.app.daemon.guard.SemaphorePullConcurrencyGuard;

/**
 * Embedded Jetty daemon server for local IPC over HTTP.
 */
public final class DaemonServer {
    private static final String CONTROL_CONNECTOR_NAME = "control";
    private static final String METRICS_CONNECTOR_NAME = "metrics";
    private final Server server;
    private final ExecutorService pullExecutor;
    private final Path unixSocketPath;

    public DaemonServer(String unixSocketPath,
                        String metricsHost,
                        int metricsPort,
                        CliApplication.ImageLoader loader,
                        Set<String> availableRuntimes,
                        int maxConcurrentPulls,
                        Duration requestTimeout,
                        AppConfig.OverloadPolicy overloadPolicy) {
        Objects.requireNonNull(unixSocketPath, "unixSocketPath");
        Objects.requireNonNull(metricsHost, "metricsHost");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(availableRuntimes, "availableRuntimes");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(overloadPolicy, "overloadPolicy");
        if (overloadPolicy != AppConfig.OverloadPolicy.REJECT) {
            throw new IllegalArgumentException("Only REJECT overload policy is supported");
        }

        this.server = new Server(new VirtualThreadPool());
        this.pullExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.unixSocketPath = Path.of(unixSocketPath);

        UnixDomainServerConnector controlConnector = new UnixDomainServerConnector(server);
        controlConnector.setName(CONTROL_CONNECTOR_NAME);
        controlConnector.setUnixDomainPath(this.unixSocketPath);
        server.addConnector(controlConnector);

        ServerConnector metricsConnector = new ServerConnector(server);
        metricsConnector.setName(METRICS_CONNECTOR_NAME);
        metricsConnector.setHost(metricsHost);
        metricsConnector.setPort(metricsPort);
        server.addConnector(metricsConnector);

        Handler.Sequence root = new Handler.Sequence();
        PullConcurrencyGuard pullConcurrencyGuard =
                new SemaphorePullConcurrencyGuard(new Semaphore(maxConcurrentPulls, true));
        root.addHandler(new PullHttpHandler(
            CONTROL_CONNECTOR_NAME,
            loader,
            availableRuntimes,
            pullConcurrencyGuard,
            requestTimeout,
            pullExecutor
        ));
        root.addHandler(new MetricsHttpHandler(METRICS_CONNECTOR_NAME));
        server.setHandler(root);
    }

    public void startAndJoin() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopQuietly));
        prepareSocketPath();
        server.start();
        server.join();
    }

    private void stopQuietly() {
        try {
            server.stop();
        } catch (Exception ignored) {
            // best effort on shutdown
        }
        deleteSocketIfExists();
        pullExecutor.shutdown();
    }

    private void prepareSocketPath() throws IOException {
        Path parent = unixSocketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(unixSocketPath);
    }

    private void deleteSocketIfExists() {
        try {
            Files.deleteIfExists(unixSocketPath);
        } catch (IOException ignored) {
            // best effort on shutdown
        }
    }
}
