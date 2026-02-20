package riid.p2p;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.p2p.config.DragonflyConfig;
import riid.p2p.config.DragonflyConnectionConfig;
import riid.p2p.config.DragonflyHealthConfig;

/**
 * Monitors Dragonfly health (endpoint + scheduler) with periodic checks.
 * State: healthy (initial) or unhealthy. Only UNHEALTHY transitions to unhealthy.
 * Logs transitions.
 */
public final class DragonflyHealthMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DragonflyHealthMonitor.class);

    private final DragonflyConfig config;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private volatile ScheduledExecutorService scheduler;

    public DragonflyHealthMonitor(DragonflyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    /** Marks unhealthy. Logs if transition from healthy. */
    public void markUnhealthy() {
        if (healthy.compareAndSet(true, false)) {
            LOGGER.warn("Dragonfly health: healthy -> unhealthy");
        }
    }

    /** Marks healthy. Logs if transition from unhealthy (recovery). */
    public void markHealthy() {
        if (healthy.compareAndSet(false, true)) {
            LOGGER.info("Dragonfly health: unhealthy -> healthy (recovery)");
        }
    }

    /** Performs health check without throwing. Returns true if healthy. */
    public boolean performCheck() {
        if (!DragonflyDaemonHealthCheck.isDaemonReachable(config)) {
            return false;
        }
        String schedulerAddr = config.connection().schedulerAddr();
        if (schedulerAddr != null && !schedulerAddr.isBlank()
                && !schedulerReachable(schedulerAddr, config.health())) {
            return false;
        }
        return true;
    }

    /** Starts periodic health check if enabled in config. */
    public void start() {
        DragonflyHealthConfig health = config.health();
        if (!health.isPeriodicCheckEnabled()) {
            return;
        }
        Duration interval = health.checkIntervalOrDefault();
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "dragonfly-health");
            t.setDaemon(true);
            return t;
        });
        long periodMillis = interval.toMillis();
        scheduler.scheduleAtFixedRate(this::runPeriodicCheck, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        LOGGER.debug("Dragonfly health periodic check started, interval {}s", interval.getSeconds());
    }

    /** Stops periodic check. */
    public void shutdown() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            try {
                s.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    private void runPeriodicCheck() {
        try {
            if (performCheck()) {
                markHealthy();
            } else {
                markUnhealthy();
            }
        } catch (Exception ex) {
            LOGGER.warn("Dragonfly health check failed: {}", ex.getMessage());
            markUnhealthy();
        }
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
