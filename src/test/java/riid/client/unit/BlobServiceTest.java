package riid.client.unit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.client.auth.AuthService;
import riid.cache.TokenCache;
import riid.client.blob.BlobRequest;
import riid.client.blob.BlobResult;
import riid.client.blob.BlobService;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlobServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsBlobWithDigestValidation() throws Exception {
        byte[] data = "hello-blob".getBytes();
        String digest = "sha256:" + sha256(data);
        setupServer(exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                respond(exchange, 200, Map.of(), new byte[0]);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                respond(exchange, 200, Map.of(), data);
                return;
            }
            respond(exchange, 404, Map.of(), new byte[0]);
        });
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpClient client = HttpClientFactory.create(cfg);
        HttpExecutor exec = new HttpExecutor(client, cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);

        // HEAD
        Optional<Long> head = blob.headBlob(ep, "repo", digest, "scope");
        assertTrue(head.isPresent());
        assertEquals(data.length, head.get());

        // GET
        File tmp = Files.createTempFile("blob-", ".bin").toFile();
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest("repo", digest, (long) data.length, "application/octet-stream");
        BlobResult result = blob.fetchBlob(ep, req, tmp, "scope");
        assertEquals(digest, result.digest());
        assertEquals(data.length, result.size());
        assertEquals(data.length, tmp.length());
    }

    @Test
    void missingContentLengthFails() throws Exception {
        byte[] data = "no-length".getBytes();
        String digest = "sha256:" + sha256(data);
        setupServer(exchange -> {
            // respond without Content-Length (chunked)
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        });
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpClient client = HttpClientFactory.create(cfg);
        HttpExecutor exec = new HttpExecutor(client, cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);

        File tmp = Files.createTempFile("blob-", ".bin").toFile();
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest("repo", digest, null, "application/octet-stream");
        assertThrows(RuntimeException.class, () -> blob.fetchBlob(ep, req, tmp, "scope"));
    }

    @Test
    void digestMismatchFails() throws Exception {
        byte[] data = "body".getBytes();
        String expectedDigest = "sha256:" + sha256("other".getBytes()); // wrong digest
        setupServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            respond(exchange, 200, Map.of(), data);
        });
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpExecutor exec = new HttpExecutor(HttpClientFactory.create(cfg), cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);
        File tmp = Files.createTempFile("blob-", ".bin").toFile();
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest("repo", expectedDigest, (long) data.length, "application/octet-stream");
        assertThrows(RuntimeException.class, () -> blob.fetchBlob(ep, req, tmp, "scope"));
    }

    @Test
    void headNotFoundIsEmpty() throws Exception {
        setupServer(exchange -> respond(exchange, 404, Map.of(), new byte[0]));
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpExecutor exec = new HttpExecutor(HttpClientFactory.create(cfg), cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);
        assertTrue(blob.headBlob(ep, "repo", "sha256:zzz", "scope").isEmpty());
    }

    @Test
    void headBadStatusFails() throws Exception {
        setupServer(exchange -> respond(exchange, 500, Map.of(), new byte[0]));
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpExecutor exec = new HttpExecutor(HttpClientFactory.create(cfg), cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);
        assertThrows(RuntimeException.class, () -> blob.headBlob(ep, "repo", "sha256:zzz", "scope"));
    }

    private void setupServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // ping
        server.createContext("/v2/", exchange -> {
            respond(exchange, 200, Map.of(), new byte[0]);
        });
        server.createContext("/v2/repo/blobs/", handler);
        server.start();
    }

    private void respond(HttpExchange exchange, int status, Map<String, String> headers, byte[] body) throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return bytesToHex(md.digest(data));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

