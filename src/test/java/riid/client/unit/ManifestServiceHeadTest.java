package riid.client.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.cache.TokenCache;
import riid.client.service.AuthService;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.service.ManifestService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestServiceHeadTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void headWithoutDigestFails() throws Exception {
        setupServer(exchange -> {
            respond(exchange, 200, Map.of("Content-Length", "10"), "");
        });
        ManifestService svc = manifestService();
        assertThrows(RuntimeException.class, () -> svc.headManifest(endpoint(), "repo", "latest", "scope"));
    }

    @Test
    void headWithoutContentLengthFails() throws Exception {
        setupServer(exchange -> {
            respond(exchange, 200, Map.of("Docker-Content-Digest", "sha256:abc"), "");
        });
        ManifestService svc = manifestService();
        assertThrows(RuntimeException.class, () -> svc.headManifest(endpoint(), "repo", "latest", "scope"));
    }

    @Test
    void headSuccess() throws Exception {
        setupServer(exchange -> {
            respond(exchange, 200, Map.of("Docker-Content-Digest", "sha256:abc", "Content-Length", "5"), "");
        });
        ManifestService svc = manifestService();
        assertTrue(svc.headManifest(endpoint(), "repo", "latest", "scope").isPresent());
    }

    private ManifestService manifestService() {
        HttpClientConfig cfg = new HttpClientConfig();
        HttpClient client = HttpClientFactory.create(cfg);
        HttpExecutor exec = new HttpExecutor(client, cfg);
        AuthService auth = new AuthService(exec, new ObjectMapper(), new TokenCache());
        return new ManifestService(exec, auth, new ObjectMapper());
    }

    private RegistryEndpoint endpoint() {
        return new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
    }

    private void setupServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/", exchange -> {
            respond(exchange, 200, Map.of(), "");
        });
        server.createContext("/v2/repo/manifests/latest", handler);
        server.start();
    }

    private void respond(HttpExchange exchange, int status, Map<String, String> headers, String body) throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

