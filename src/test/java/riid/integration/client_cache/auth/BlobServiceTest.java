package riid.integration.client_cache.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.cache.auth.TokenCache;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.service.AuthService;
import riid.client.service.BlobService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

@SuppressWarnings("PMD.CloseResource")
class BlobServiceTest {
    private final HostFilesystem fs = new NioHostFilesystem();
    private enum Strings {
        HTTP_SCHEME("http"),
        HOST("localhost"),
        REPO("repo"),
        SCOPE("scope"),
        CONTENT_TYPE("Content-Type"),
        OCTET("application/octet-stream"),
        METHOD_HEAD("HEAD"),
        METHOD_GET("GET");

        private final String value;

        Strings(String value) {
            this.value = value;
        }

        String v() {
            return value;
        }
    }

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsBlobWithDigestValidation() throws Exception {
        byte[] data = "hello-blob".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(data);
        setupServer(exchange -> {
            String method = exchange.getRequestMethod();
            if (Strings.METHOD_HEAD.v().equals(method)) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
                exchange.getResponseHeaders().add(Strings.CONTENT_TYPE.v(), Strings.OCTET.v());
                respond(exchange, 200, Map.of(), new byte[0]);
                return;
            }
            if (Strings.METHOD_GET.v().equals(method)) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
                exchange.getResponseHeaders().add(Strings.CONTENT_TYPE.v(), Strings.OCTET.v());
                respond(exchange, 200, Map.of(), data);
                return;
            }
            respond(exchange, 404, Map.of(), new byte[0]);
        });
        RegistryEndpoint ep = new RegistryEndpoint(
                Strings.HTTP_SCHEME.v(),
                Strings.HOST.v(),
                server.getAddress().getPort(),
                null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpClient client = HttpClientFactory.create(cfg);
        HttpExecutor exec = new HttpExecutor(client, cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);

        // HEAD
        Optional<Long> head = blob.headBlob(ep, Strings.REPO.v(), digest, Strings.SCOPE.v());
        assertTrue(head.isPresent());
        assertEquals(data.length, head.get());

        // GET
        File tmp = TestPaths.tempFile(fs, "blob-", ".bin").toFile();
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest(Strings.REPO.v(), digest, (long) data.length, Strings.OCTET.v());
        BlobResult result = blob.fetchBlob(ep, req, tmp, Strings.SCOPE.v());
        assertEquals(digest, result.digest());
        assertEquals(data.length, result.size());
        assertEquals(data.length, tmp.length());
    }

    @Test
    void missingContentLengthFails() throws Exception {
        byte[] data = "no-length".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(data);
        setupServer(exchange -> {
            // respond without Content-Length (chunked)
            exchange.getResponseHeaders().add(Strings.CONTENT_TYPE.v(), Strings.OCTET.v());
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        });
        RegistryEndpoint ep = new RegistryEndpoint(
                Strings.HTTP_SCHEME.v(),
                Strings.HOST.v(),
                server.getAddress().getPort(),
                null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpClient client = HttpClientFactory.create(cfg);
        HttpExecutor exec = new HttpExecutor(client, cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);

        File tmp = TestPaths.tempFile(fs, "blob-", ".bin").toFile();
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest(Strings.REPO.v(), digest, null, Strings.OCTET.v());
        assertThrows(RuntimeException.class, () -> blob.fetchBlob(ep, req, tmp, Strings.SCOPE.v()));
    }

    @Test
    void digestMismatchFails() throws Exception {
        byte[] data = "body".getBytes(StandardCharsets.UTF_8);
        String expectedDigest = "sha256:" + sha256("other".getBytes(StandardCharsets.UTF_8));
        setupServer(exchange -> {
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
            exchange.getResponseHeaders().add(Strings.CONTENT_TYPE.v(), Strings.OCTET.v());
            respond(exchange, 200, Map.of(), data);
        });
        RegistryEndpoint ep = new RegistryEndpoint(
                Strings.HTTP_SCHEME.v(),
                Strings.HOST.v(),
                server.getAddress().getPort(),
                null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpExecutor exec = new HttpExecutor(HttpClientFactory.create(cfg), cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);
        File tmp = TestPaths.tempFile(fs, "blob-", ".bin").toFile();
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest(Strings.REPO.v(), expectedDigest, (long) data.length, Strings.OCTET.v());
        assertThrows(RuntimeException.class, () -> blob.fetchBlob(ep, req, tmp, Strings.SCOPE.v()));
    }

    @Test
    void headNotFoundIsEmpty() throws Exception {
        setupServer(exchange -> respond(exchange, 404, Map.of(), new byte[0]));
        RegistryEndpoint ep = new RegistryEndpoint(
                Strings.HTTP_SCHEME.v(),
                Strings.HOST.v(),
                server.getAddress().getPort(),
                null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpExecutor exec = new HttpExecutor(HttpClientFactory.create(cfg), cfg);
        AuthService auth = new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);
        assertTrue(blob.headBlob(ep, Strings.REPO.v(), "sha256:zzz", Strings.SCOPE.v()).isEmpty());
    }

    @Test
    void headBadStatusFails() throws Exception {
        setupServer(exchange -> respond(exchange, 500, Map.of(), new byte[0]));
        RegistryEndpoint ep = new RegistryEndpoint(
                Strings.HTTP_SCHEME.v(),
                Strings.HOST.v(),
                server.getAddress().getPort(),
                null);
        HttpClientConfig cfg = new HttpClientConfig();
        HttpExecutor exec = new HttpExecutor(HttpClientFactory.create(cfg), cfg);
        AuthService auth =
                new AuthService(exec, new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache());
        BlobService blob = new BlobService(exec, auth, null);
        assertThrows(
                RuntimeException.class,
                () -> blob.headBlob(ep, Strings.REPO.v(), "sha256:zzz", Strings.SCOPE.v()));
    }

    private void setupServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // ping
        server.createContext("/v2/", exchange -> {
            respond(exchange, 200, Map.of(), new byte[0]);
        });
        server.createContext("/v2/" + Strings.REPO.v() + "/blobs/", handler);
        server.start();
    }

    private void respond(HttpExchange exchange,
                         int status,
                         Map<String, String> headers,
                         byte[] body) throws IOException {
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

