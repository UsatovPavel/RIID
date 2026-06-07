package riid.integration.p2p;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.sun.net.httpserver.HttpServer;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.p2p.dragonfly.DragonflyConfig;
import riid.p2p.dragonfly.DragonflyGrpcP2PExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;

/**
 * Integration tests for RIID P2P adapter on top of external Dragonfly puller
 * library. Verifies behavior contract: first fetch may go back-to-source,
 * second fetch is served from dfdaemon local cache for the same digest. Run:
 * ./scripts/minikube-dragonfly.sh && make dragonfly-integration-test Or:
 * DFDAEMON_ADDR=unix:///var/run/dragonfly/dfdaemon.sock \ ./gradlew
 * integrationTest -PincludeLocal --tests DragonflySingleP2PExecutorTest
 */
@Tag("local")
@Tag("filesystem")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DragonflySingleP2PExecutorTest {

    private static final String REPO = "repo";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String DFDAEMON_ADDR = System.getenv().getOrDefault("DFDAEMON_ADDR",
            "unix:///var/run/dragonfly/dfdaemon.sock");

    private static HttpServer server;
    private static byte[] payload;
    private static String digest;
    private static AtomicInteger blobRequests;
    private static String expectedAuthHeader;
    private static RegistryEndpoint endpoint;
    private static HostFilesystem fs;
    private static DragonflyGrpcP2PExecutor p2p;

    @BeforeAll
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Unix socket path is the canonical Dragonfly location in this integration test")
    static void setUp() throws Exception {
        boolean useUnix = DFDAEMON_ADDR.startsWith("unix://");
        Assumptions.assumeTrue(!useUnix || Files.exists(Path.of("/var/run/dragonfly/dfdaemon.sock")),
                "dfdaemon socket not found (run ./minikube-dragonfly.sh)");
        Assumptions.assumeTrue(useUnix || System.getenv("DFDAEMON_OUTPUT_DIR") != null,
                "for TCP set DFDAEMON_OUTPUT_DIR=/var/run/dragonfly/output");

        payload = "p2p-single-cache-test".getBytes(StandardCharsets.UTF_8);
        digest = "sha256:" + sha256(payload);
        blobRequests = new AtomicInteger();
        expectedAuthHeader = "Basic "
                + Base64.getEncoder().encodeToString("riid-user:riid-secret".getBytes(StandardCharsets.UTF_8));

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
            }
        });
        server.createContext("/v2/" + REPO + "/blobs/" + digest, exchange -> {
            try (exchange) {
                blobRequests.incrementAndGet();
                assertEquals(expectedAuthHeader, exchange.getRequestHeaders().getFirst("Authorization"),
                        "dfdaemon should pass registry auth from RegistryPullRequest");
                exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE);
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(payload.length));
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
        });
        server.start();

        endpoint = new RegistryEndpoint("http", "127.0.0.1", server.getAddress().getPort(),
                Credentials.basic("riid-user", "riid-secret"));
        fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null, null);
        p2p = new DragonflyGrpcP2PExecutor(endpoint, config);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Order(1)
    void firstFetchMayUseBackToSource() throws IOException {
        var result = p2p.fetch(REPO, ImageDigest.parse(digest), payload.length, CacheMediaType.OCTET_STREAM);

        assertTrue(result.isPresent(), "first fetch should succeed");
        Path path = result.get();
        assertNotNull(path);
        assertTrue(fs.exists(path), "downloaded file should exist");
        assertEquals(payload.length, fs.size(path), "downloaded size should match");
        assertTrue(blobRequests.get() >= 1, "first fetch should hit registry (back-to-source)");
    }

    @Test
    @Order(2)
    void secondFetchUsesDfdaemonLocalCache() throws IOException {
        int requestsBefore = blobRequests.get();

        var result = p2p.fetch(REPO, ImageDigest.parse(digest), payload.length, CacheMediaType.OCTET_STREAM);

        assertTrue(result.isPresent(), "second fetch should succeed");
        assertEquals(payload.length, fs.size(result.get()), "second fetch size should match");
        assertEquals(requestsBefore, blobRequests.get(),
                "second fetch should not hit registry — blob served from dfdaemon cache");
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
