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

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import riid.app.cli.CliApplication;
import riid.app.core.config.AppConfig;
import riid.app.daemon.guard.PullConcurrencyGuard;
import riid.app.daemon.guard.SemaphorePullConcurrencyGuard;
import riid.app.daemon.handler.MetricsHttpHandler;
import riid.app.daemon.handler.PullHttpHandler;
import riid.app.daemon.metrics.DaemonPullHttpMetrics;
import riid.app.daemon.metrics.ImageLoadPipelineMetrics;

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
                        AppConfig.OverloadPolicy overloadPolicy,
                        PrometheusMeterRegistry prometheusRegistry) {
        Objects.requireNonNull(unixSocketPath, "unixSocketPath");
        Objects.requireNonNull(metricsHost, "metricsHost");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(availableRuntimes, "availableRuntimes");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(overloadPolicy, "overloadPolicy");
        Objects.requireNonNull(prometheusRegistry, "prometheusRegistry");
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
            pullExecutor,
            new DaemonPullHttpMetrics(prometheusRegistry),
            new ImageLoadPipelineMetrics(prometheusRegistry)
        ));
        root.addHandler(new MetricsHttpHandler(METRICS_CONNECTOR_NAME, prometheusRegistry));
        server.setHandler(root);
    }

    /**
     * TCP listen port for the metrics connector (after {@link #start()}). {@code -1} if not bound.
     */
    public int getMetricsListenPort() {
        for (var connector : server.getConnectors()) {
            if (METRICS_CONNECTOR_NAME.equals(connector.getName()) && connector instanceof ServerConnector sc) {
                return sc.getLocalPort();
            }
        }
        return -1;
    }

    /**
     * Starts the server (UDS + metrics TCP). Does not block; pair with {@link #stop()} in tests or embedders.
     */
    public void start() throws Exception {
        prepareSocketPath();
        server.start();
    }

    /**
     * Stops Jetty and releases the pull executor; deletes the Unix socket file if present.
     */
    public void stop() throws Exception {
        try {
            server.stop();
        } finally {
            deleteSocketIfExists();
            pullExecutor.shutdown();
        }
    }

    public void startAndJoin() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stop();
            } catch (Exception ignored) {
                // best effort on shutdown
            }
        }));
        start();
        server.join();
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
