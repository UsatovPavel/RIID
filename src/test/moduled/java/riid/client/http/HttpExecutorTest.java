package riid.client.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
class HttpExecutorTest {
    private static final int FIRST_CALL = 1;

    private HttpServer server;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesOnlyIdempotentWhenConfigured() throws Exception {
        HttpClient client = new HttpClient();
        HttpClientConfig config = HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(1)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(200))
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build();
        HttpExecutor exec = new HttpExecutor(client, config);

        var retryStatusEx = assertThrows(IllegalStateException.class, () -> exec.shouldRetry(503, 1, false));
        var retryIoEx = assertThrows(IllegalStateException.class, () -> exec.shouldRetryIOException(1, false));
        assertTrue(retryStatusEx.getMessage().contains("idempotent"));
        assertTrue(retryIoEx.getMessage().contains("idempotent"));
    }

    @Test
    void retriesLimitedByAttemptsAndStatus() throws Exception {
        HttpExecutor exec = new HttpExecutor(new HttpClient(), new HttpClientConfig());

        assertTrue(exec.shouldRetry(503, 1, true));
        assertFalse(exec.shouldRetry(200, 1, true));
        assertFalse(exec.shouldRetry(503, 5, true)); // exceeds maxRetries (default 2)
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
        String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
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

    @Test
    void retriesOnIOExceptionThenSucceeds() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        AtomicInteger calls = new AtomicInteger();
        Thread delayedServer = new Thread(() -> {
            try {
                Thread.sleep(20);
                setupServerOnPort(port, exchange -> {
                    calls.incrementAndGet();
                    respond(exchange, 200, Map.of(), "ok");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        delayedServer.start();

        HttpExecutor exec = executor(3, Duration.ofMillis(100));
        var resp = exec.get(URI.create("http://localhost:" + port + "/io"), Map.of());

        assertEquals(200, resp.statusCode());
        String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("ok", body);
        assertEquals(1, calls.get(), "server should eventually receive the successful retry");
        delayedServer.join(1000);
    }

    private HttpExecutor executor(int maxRetries) {
        return executor(maxRetries, Duration.ofMillis(10));
    }

    private HttpExecutor executor(int maxRetries, Duration backoff) {
        HttpClientConfig cfg = HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(maxRetries)
                .initialBackoff(backoff)
                .maxBackoff(backoff)
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build();
        var client = HttpClientFactory.create(cfg);
        return new HttpExecutor(client, cfg);
    }

    private void setupServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private void setupServerOnPort(int port, HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
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

