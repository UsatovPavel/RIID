package riid.client.resilence;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.cache.CacheAdapter;
import riid.client.auth.AuthService;
import riid.client.auth.TokenCache;
import riid.client.blob.BlobRequest;
import riid.client.blob.BlobResult;
import riid.client.blob.BlobService;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("resilience")
class BlobRetryOffsetTest {

    @Test
    void resumesDownloadAfterAbort() throws Exception {
        byte[] payload = generateData(1024 * 1024); // 1 MB
        String digest = "sha256:" + sha256Hex(payload);

        try (TestServer server = new TestServer(payload, digest)) {
            server.start();

            RegistryEndpoint endpoint = new RegistryEndpoint("http", "localhost", server.port(), null);
            HttpClientConfig cfg = HttpClientConfig.builder().build();
            HttpClient client = HttpClientFactory.create(cfg);
            HttpExecutor http = new HttpExecutor(client, cfg);
            AuthService auth = new AuthService(http, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
            BlobService blobService = new BlobService(http, auth, (CacheAdapter) null);

            File tmp = Files.createTempFile("resume-test", ".bin").toFile();
            tmp.deleteOnExit();

            BlobRequest req = new BlobRequest("repo", digest, (long) payload.length, "application/octet-stream");

            // first attempt with resume=true: server will drop connection mid-stream
            try {
                blobService.fetchBlob(endpoint, req, tmp, "scope", true);
            } catch (Exception e) {
                // expected due to aborted connection
            }

            // second attempt should resume and succeed
            BlobResult result = blobService.fetchBlob(endpoint, req, tmp, "scope", true);
            assertEquals(digest, result.digest());
            assertEquals(payload.length, tmp.length());
            assertTrue(tmp.length() > 0);
        }
    }

    private static byte[] generateData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(data);
        return bytesToHex(md.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit((b) & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Simple HTTP server that supports Range and aborts the first response mid-stream to simulate connection drop.
     */
    private static class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final int port;

        TestServer(byte[] payload, String digest) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.port = server.getAddress().getPort();
            AtomicBoolean first = new AtomicBoolean(true);
            server.createContext("/v2/repo/blobs/" + digest, new Handler(payload, first));
            server.setExecutor(Executors.newCachedThreadPool());
        }

        int port() {
            return port;
        }

        void start() {
            server.start();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static class Handler implements HttpHandler {
            private final byte[] payload;
            private final AtomicBoolean first;

            Handler(byte[] payload, AtomicBoolean first) {
                this.payload = payload;
                this.first = first;
            }

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Headers h = exchange.getResponseHeaders();
                h.add("Content-Type", "application/octet-stream");
                h.add("Docker-Content-Digest", "sha256:" + sha256(payload));

                long start = 0;
                long end = payload.length - 1;
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null && range.startsWith("bytes=")) {
                    String[] parts = range.substring("bytes=".length()).split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isBlank()) {
                        end = Long.parseLong(parts[1]);
                    }
                    h.add("Content-Range", "bytes %d-%d/%d".formatted(start, end, payload.length));
                    exchange.sendResponseHeaders(206, end - start + 1);
                } else {
                    exchange.sendResponseHeaders(200, payload.length);
                }

                OutputStream os = exchange.getResponseBody();
                // On first request, abort mid-stream
                if (first.getAndSet(false)) {
                    int mid = (int) ((start + payload.length) / 2);
                    os.write(payload, (int) start, mid - (int) start);
                    os.flush();
                    os.close(); // abort
                    return;
                }
                os.write(payload, (int) start, (int) (end - start + 1));
                os.close();
            }

            private static String sha256(byte[] data) {
                try {
                    var md = java.security.MessageDigest.getInstance("SHA-256");
                    md.update(data);
                    return bytesToHex(md.digest());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
