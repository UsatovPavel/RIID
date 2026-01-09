package riid.client.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.cache.CacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.TagList;
import riid.client.http.HttpClientConfig;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegistryClientImplTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchManifestBlobAndTagsSuccess() throws Exception {
        byte[] layer = "layer-data".getBytes();
        String layerDigest = "sha256:" + sha256(layer);
        Manifest manifest = manifest(layerDigest, layer.length);
        byte[] manifestBytes = new ObjectMapper().writeValueAsBytes(manifest);
        String manifestDigest = "sha256:" + sha256(manifestBytes);

        startServer(layer, layerDigest, manifestBytes, manifestDigest, 200, 200);

        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        RegistryClient client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null);

        var mf = client.fetchManifest("repo", "latest");
        assertEquals(manifestDigest, mf.digest());
        assertEquals(1, mf.manifest().layers().size());
        assertEquals(layerDigest, mf.manifest().layers().getFirst().digest());

        File tmp = File.createTempFile("blob-", ".bin");
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest("repo", layerDigest, (long) layer.length, "application/octet-stream");
        BlobResult br = client.fetchBlob(req, tmp);
        assertEquals(layerDigest, br.digest());
        assertEquals(layer.length, br.size());
        assertTrue(tmp.length() > 0);

        TagList tags = client.listTags("repo", null, null);
        assertEquals("repo", tags.name());
        assertTrue(tags.tags().contains("latest"));
        assertTrue(tags.tags().contains("edge"));
    }

    @Test
    void listTagsErrorThrows() throws Exception {
        startServer(new byte[0], "sha256:dead", new byte[0], "sha256:dead", 500, 500);
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        RegistryClient client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null);
        assertThrows(RuntimeException.class, () -> client.listTags("repo", null, null));
    }

    @Test
    void headBlobNotFound() throws Exception {
        // only HEAD returns 404
        startServerHeadOnly404();
        RegistryEndpoint ep = new RegistryEndpoint("http", "localhost", server.getAddress().getPort(), null);
        RegistryClient client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null);
        assertTrue(client.headBlob("repo", "sha256:missing").isEmpty());
    }

    private void startServer(byte[] layer,
                             String layerDigest,
                             byte[] manifestBytes,
                             String manifestDigest,
                             int tagsStatus,
                             int blobStatus) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/", exchange -> respond(exchange, 200, Map.of(), ""));
        server.createContext("/v2/repo/manifests/latest", exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                respond(exchange, 405, Map.of(), "");
                return;
            }
            Map<String, String> headers = Map.of(
                    "Content-Type", "application/vnd.docker.distribution.manifest.v2+json",
                    "Docker-Content-Digest", manifestDigest
            );
            respond(exchange, 200, headers, manifestBytes);
        });
        server.createContext("/v2/repo/blobs/" + layerDigest, exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                if (blobStatus == 404) {
                    respond(exchange, 404, Map.of(), new byte[0]);
                    return;
                }
                respond(exchange, 200, Map.of(
                        "Content-Length", String.valueOf(layer.length),
                        "Content-Type", "application/octet-stream"
                ), new byte[0]);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, blobStatus, Map.of(
                        "Content-Length", String.valueOf(layer.length),
                        "Content-Type", "application/octet-stream"
                ), layer);
                return;
            }
            respond(exchange, 405, Map.of(), new byte[0]);
        });
        server.createContext("/v2/repo/tags/list", exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                respond(exchange, 405, Map.of(), "");
                return;
            }
            String body = "{\"name\":\"repo\",\"tags\":[\"latest\",\"edge\"]}";
            respond(exchange, tagsStatus, Map.of("Content-Type", "application/json"), body);
        });
        server.start();
    }

    private void startServerHeadOnly404() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/", exchange -> respond(exchange, 200, Map.of(), ""));
        server.createContext("/v2/repo/blobs/sha256:missing", exchange -> respond(exchange, 404, Map.of(), new byte[0]));
        server.start();
    }

    private void respond(HttpExchange exchange, int status, Map<String, String> headers, String body) throws IOException {
        respond(exchange, status, headers, body.getBytes());
    }

    private void respond(HttpExchange exchange, int status, Map<String, String> headers, byte[] body) throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private Manifest manifest(String layerDigest, long size) {
        Descriptor layer = new Descriptor("application/octet-stream", layerDigest, size);
        Descriptor cfg = new Descriptor("application/vnd.docker.container.image.v1+json",
                "sha256:" + layerDigest.substring("sha256:".length()), size);
        return new Manifest(2, "application/vnd.docker.distribution.manifest.v2+json", cfg, List.of(layer));
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

