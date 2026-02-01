package riid.client.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.TagList;
import riid.client.http.HttpClientConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryClientImplListTagsTest {

    private static final String REPO = "repo";
    private static final String V2 = "/v2/";

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listTagsSuccess() throws Exception {
        server.createContext(V2, new JsonHandler("", 200));
        String body = String.format("{\"name\":\"%s\",\"tags\":[\"latest\",\"edge\"]}", REPO);
        server.createContext(V2 + REPO + "/tags/list", new JsonHandler(body, 200));

        try (RegistryClient client = new RegistryClientImpl(
                new RegistryEndpoint("http", "localhost", port, null),
                new HttpClientConfig(),
                null)) {
            TagList list = client.listTags(REPO, null, null);
            assertEquals(REPO, list.name());
            assertEquals(2, list.tags().size());
        }
    }

    @Test
    void listTagsBadStatusThrows() throws Exception {
        server.createContext(V2, new JsonHandler("", 200));
        server.createContext(V2 + REPO + "/tags/list", new JsonHandler("{}", 503));

        try (RegistryClient client = new RegistryClientImpl(
                new RegistryEndpoint("http", "localhost", port, null),
                new HttpClientConfig(),
                null)) {
            assertThrows(ClientException.class, () -> client.listTags(REPO, null, null));
        }
    }

    @Test
    void listTagsParseErrorThrows() throws Exception {
        server.createContext(V2, new JsonHandler("", 200));
        server.createContext(V2 + REPO + "/tags/list", new JsonHandler("{not-json", 200));

        try (RegistryClient client = new RegistryClientImpl(
                new RegistryEndpoint("http", "localhost", port, null),
                new HttpClientConfig(),
                null)) {
            assertThrows(ClientException.class, () -> client.listTags(REPO, null, null));
        }
    }

    private static final class JsonHandler implements HttpHandler {
        private final String body;
        private final int status;

        private JsonHandler(String body, int status) {
            this.body = body;
            this.status = status;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}

