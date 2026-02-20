package riid.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.p2p.config.DragonflyConfig;
import riid.p2p.config.DragonflyConnectionConfig;
import riid.p2p.config.DragonflyHealthConfig;
import riid.p2p.config.DragonflyRequestConfig;

/**
 * Module tests: Dragonfly adapter error mapping and health-check policy.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class DragonflyAdapterTest {

    // ------------ DragonflyClientException

    @Test
    void dragonflyClientExceptionPreservesKindAndMessage() {
        DragonflyClientException ex = new DragonflyClientException(
                DragonflyClientException.ErrorKind.UNHEALTHY,
                "dragonfly endpoint is unavailable");
        assertEquals(DragonflyClientException.ErrorKind.UNHEALTHY, ex.kind());
        assertEquals("dragonfly endpoint is unavailable", ex.getMessage());
    }

    @Test
    void dragonflyClientExceptionWithCause() {
        IOException cause = new IOException("connect failed");
        DragonflyClientException ex = new DragonflyClientException(
                DragonflyClientException.ErrorKind.TIMEOUT,
                "dfget timed out",
                cause);
        assertEquals(DragonflyClientException.ErrorKind.TIMEOUT, ex.kind());
        assertEquals(cause, ex.getCause());
    }

    @Test
    void allErrorKindsExist() {
        for (DragonflyClientException.ErrorKind k : DragonflyClientException.ErrorKind.values()) {
            assertNotNull(k);
            DragonflyClientException ex = new DragonflyClientException(k, "msg");
            assertEquals(k, ex.kind());
        }
    }

    // ------------ DragonflyDaemonHealthCheck (via reflection or package visibility - use adapter path)

    @Tag("filesystem")
    @Test
    void daemonUnreachableWhenSocketMissing() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path missing = Path.of("build/test-fs/nonexistent-dfdaemon-" + System.nanoTime() + ".sock");
        DragonflyConfig config = configWithEndpoint(missing.toString());
        assertFalse(DragonflyDaemonHealthCheck.isDaemonReachable(config));
    }

    @Tag("filesystem")
    @Test
    void daemonReachableWhenSocketExists() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path sock = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "dfdaemon-", ".sock");
        DragonflyConfig config = configWithEndpoint(sock.toString());
        assertTrue(DragonflyDaemonHealthCheck.isDaemonReachable(config));
    }

    @Tag("filesystem")
    @Test
    void daemonUnreachableWhenGrpcHealthProbeConfiguredButBinaryFails() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path sock = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "dfdaemon-", ".sock");
        DragonflyHealthConfig health = new DragonflyHealthConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                "/nonexistent/grpc_health_probe_" + System.nanoTime());
        DragonflyConfig config = new DragonflyConfig(
                true,
                new DragonflyConnectionConfig("dfget", null, sock.toString(), null),
                new DragonflyRequestConfig(Duration.ofMinutes(2), 0, null, null, List.of()),
                null,
                health);
        assertFalse(DragonflyDaemonHealthCheck.isDaemonReachable(config));
    }

    // ------------ DragonflyHealthMonitor

    @Test
    void healthMonitorStartsHealthy() {
        DragonflyConfig config = minimalConfig();
        DragonflyHealthMonitor monitor = new DragonflyHealthMonitor(config);
        assertTrue(monitor.isHealthy());
    }

    @Test
    void healthMonitorMarkUnhealthyTransitions() {
        DragonflyHealthMonitor monitor = new DragonflyHealthMonitor(minimalConfig());
        assertTrue(monitor.isHealthy());
        monitor.markUnhealthy();
        assertFalse(monitor.isHealthy());
    }

    @Test
    void healthMonitorMarkHealthyAfterUnhealthyRecovery() {
        DragonflyHealthMonitor monitor = new DragonflyHealthMonitor(minimalConfig());
        monitor.markUnhealthy();
        assertFalse(monitor.isHealthy());
        monitor.markHealthy();
        assertTrue(monitor.isHealthy());
    }

    @Test
    void healthMonitorShutdownDoesNotThrow() {
        DragonflyHealthMonitor monitor = new DragonflyHealthMonitor(minimalConfig());
        monitor.start();
        monitor.shutdown();
    }

    // ------------ DragonflyHealthConfig

    @Test
    void healthConfigCheckIntervalDefaults() {
        DragonflyHealthConfig h = new DragonflyHealthConfig();
        assertEquals(Duration.ofSeconds(30), h.checkIntervalOrDefault());
        assertTrue(h.isPeriodicCheckEnabled());
    }

    @Test
    void healthConfigGrpcHealthProbePathWhenNull() {
        DragonflyHealthConfig h = new DragonflyHealthConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                null);
        assertTrue(h.grpcHealthProbePathOrDefault() == null);
    }

    @Test
    void healthConfigGrpcHealthProbePathWhenSet() {
        DragonflyHealthConfig h = new DragonflyHealthConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                "/usr/bin/grpc_health_probe");
        assertEquals("/usr/bin/grpc_health_probe", h.grpcHealthProbePathOrDefault());
    }

    private static DragonflyConfig minimalConfig() {
        return new DragonflyConfig(
                true,
                new DragonflyConnectionConfig("dfget", null, "/tmp/dfdaemon.sock", "localhost:8002"),
                new DragonflyRequestConfig(Duration.ofMinutes(2), 0, null, null, List.of()),
                null,
                new DragonflyHealthConfig());
    }

    private static DragonflyConfig configWithEndpoint(String endpoint) {
        return new DragonflyConfig(
                true,
                new DragonflyConnectionConfig("dfget", null, endpoint, null),
                new DragonflyRequestConfig(Duration.ofMinutes(2), 0, null, null, List.of()),
                null,
                new DragonflyHealthConfig());
    }
}
