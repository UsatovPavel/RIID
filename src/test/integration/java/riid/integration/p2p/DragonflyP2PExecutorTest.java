package riid.integration.p2p;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.p2p.DragonflyConfig;
import riid.p2p.DragonflyP2PExecutor;

@Tag("local")
@Tag("filesystem")
class DragonflyP2PExecutorTest {
    private static final String REPO = "repo";
    private static final String CONTENT_TYPE = "application/octet-stream";

    private HttpServer server;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesBlobViaDfgetAndCaches() throws Exception {
        String dfgetPath = dfgetPath();
        ensureDfgetAvailable(dfgetPath);

        byte[] payload = "p2p-test-payload".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(payload);
        startServer(payload, digest);

        RegistryEndpoint endpoint = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, dfgetPath, null, null, null);

        try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs)) {
            DragonflyP2PExecutor p2p = new DragonflyP2PExecutor(endpoint, cache, fs, config);

            var result = p2p.fetch(REPO, ImageDigest.parse(digest), payload.length, CacheMediaType.OCTET_STREAM);

            assertTrue(result.isPresent(), "dfget result should be present");
            Path path = result.get();
            assertNotNull(path);
            assertTrue(fs.exists(path), "downloaded file should exist");
            assertEquals(payload.length, fs.size(path), "downloaded size should match");
            assertTrue(cache.has(ImageDigest.parse(digest)), "cache should contain fetched layer");
        }
    }

    private void startServer(byte[] payload, String digest) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
            }
        });
        server.createContext("/v2/" + REPO + "/blobs/" + digest, exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE);
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(payload.length));
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
        });
        server.start();
    }

    private static String dfgetPath() {
        String env = System.getenv("DFGET_PATH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String resourcePath = DragonflyP2PExecutorTest.class
                .getResource("/dfget.sh") != null
                        ? DragonflyP2PExecutorTest.class.getResource("/dfget.sh").getPath()
                        : null;
        return resourcePath != null ? resourcePath : "dfget";
    }

    private static void ensureDfgetAvailable(String dfgetPath) {
        try {
            Process process = new ProcessBuilder(dfgetPath, "--help")
                    .redirectErrorStream(true)
                    .start();
            int code = process.waitFor();
            assertTrue(code == 0, "dfget is not available");
        } catch (IOException e) {
            throw new AssertionError("dfget is not available", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("dfget is not available", e);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
