package riid.client.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.cache.TokenCache;
import riid.client.service.AuthService;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyWhenPing200() throws Exception {
        setupServer(exchange -> respond(exchange, 200, Map.of(), ""));
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        AuthService auth = authService();
        Optional<String> hdr = auth.getAuthHeader(ep, "repo", "scope");
        assertTrue(hdr.isEmpty(), "no auth header when ping 200");
    }

    @Test
    void fetchesTokenOn401WithChallenge() throws Exception {
        String token = "abc123";
        setupServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v2/")) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"http://localhost:" + server.getAddress().getPort() + "/token\",service=\"registry\",scope=\"repo:pull\"");
                respond(exchange, 401, Map.of(), "");
            } else if (path.equals("/token")) {
                respond(exchange, 200, Map.of(), "{\"token\":\"" + token + "\",\"expires_in\":120}");
            } else {
                respond(exchange, 404, Map.of(), "");
            }
        });
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), Credentials.basic("u", "p"));
        AuthService auth = authService();
        Optional<String> hdr = auth.getAuthHeader(ep, "repo", "repo:pull");
        assertTrue(hdr.isPresent());
        assertEquals("Bearer " + token, hdr.get());
    }

    @Test
    void missingChallengeThrows() throws Exception {
        setupServer(exchange -> respond(exchange, 401, Map.of(), ""));
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        AuthService auth = authService();
        assertThrows(RuntimeException.class, () -> auth.getAuthHeader(ep, "repo", "scope"));
    }

    private AuthService authService() {
        HttpClientConfig cfg = new HttpClientConfig();
        HttpClient client = HttpClientFactory.create(cfg);
        return new AuthService(new HttpExecutor(client, cfg), new ObjectMapper(), new TokenCache());
    }

    private void setupServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
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

