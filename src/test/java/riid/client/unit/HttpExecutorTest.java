package riid.client.unit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpExecutorTest {
    private static final int FIRST_CALL = 1;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesOn503ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        setupServer(exchange -> {
            int n = calls.incrementAndGet();
            if (n == FIRST_CALL) {
                respond(exchange, 503, Map.of(), "");
            } else {
                respond(exchange, 200, Map.of(), "ok");
            }
        });
        HttpExecutor exec = executor(1); // allow 1 retry => 2 attempts
        var resp = exec.get(uri("/ok"), Map.of());
        assertEquals(200, resp.statusCode());
        String body = new String(
                resp.body().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("ok", body);
        assertEquals(2, calls.get(), "should retry once then succeed");
    }

    @Test
    void stopsAfterMaxRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        setupServer(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, Map.of(), "");
        });
        HttpExecutor exec = executor(1); // 1 retry => 2 total attempts
        var resp = exec.get(uri("/fail"), Map.of());
        assertEquals(503, resp.statusCode());
        assertEquals(2, calls.get(), "should stop after max retries + first attempt");
    }

    private HttpExecutor executor(int maxRetries) {
        HttpClientConfig cfg = new HttpClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                maxRetries,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                true,
                "test-agent");
        HttpClient client = HttpClientFactory.create(cfg);
        return new HttpExecutor(client, cfg);
    }

    private void setupServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    private void respond(HttpExchange exchange,
                         int status,
                         Map<String, String> headers,
                         String body) throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

