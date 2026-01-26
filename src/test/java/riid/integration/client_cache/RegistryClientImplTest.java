package riid.integration.client_cache;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import riid.cache.oci.CacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.TagList;
import riid.client.http.HttpClientConfig;

class RegistryClientImplTest {
    private static final String SHA_PREFIX = "sha256:";
    private static final String REPO = "repo";
    private static final String OCTET = "application/octet-stream";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String SCHEME_HTTP = "http";
    private static final String HOST_LOCALHOST = "localhost";
    private static final String METHOD_GET = HttpMethod.GET.asString();
    private static final String METHOD_HEAD = HttpMethod.HEAD.asString();
    private static final String API_PREFIX = "/v2/";
    private static final int STATUS_OK = HttpStatus.OK_200;
    private static final int STATUS_NOT_FOUND = HttpStatus.NOT_FOUND_404;
    private static final int STATUS_METHOD_NOT_ALLOWED = HttpStatus.METHOD_NOT_ALLOWED_405;

    private HttpServer server;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchManifestBlobAndTagsSuccess() throws Exception {
        byte[] layer = "layer-data".getBytes(StandardCharsets.UTF_8);
        String layerDigest = SHA_PREFIX + sha256(layer);
        Manifest manifest = manifest(layerDigest, layer.length);
        byte[] manifestBytes = new ObjectMapper().writeValueAsBytes(manifest);
        String manifestDigest = SHA_PREFIX + sha256(manifestBytes);

        startServer(layer, layerDigest, manifestBytes, manifestDigest, 200, 200);

        RegistryEndpoint ep = new RegistryEndpoint(SCHEME_HTTP, HOST_LOCALHOST, server.getAddress().getPort(), null);
        try (RegistryClientImpl client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null)) {

        var mf = client.fetchManifest(REPO, "latest");
        assertEquals(manifestDigest, mf.digest());
        assertEquals(1, mf.manifest().layers().size());
        assertEquals(layerDigest, mf.manifest().layers().getFirst().digest());

        File tmp = File.createTempFile("blob-", ".bin");
        tmp.deleteOnExit();
        BlobRequest req = new BlobRequest(REPO, layerDigest, (long) layer.length, OCTET);
        BlobResult br = client.fetchBlob(req, tmp);
        assertEquals(layerDigest, br.digest());
        assertEquals(layer.length, br.size());
        assertTrue(tmp.length() > 0);

        TagList tags = client.listTags(REPO, null, null);
        assertEquals(REPO, tags.name());
        assertTrue(tags.tags().contains("latest"));
        assertTrue(tags.tags().contains("edge"));
        }
    }

    @Test
    void listTagsErrorThrows() throws Exception {
        startServer(new byte[0], "sha256:dead", new byte[0], "sha256:dead", 500, 500);
        RegistryEndpoint ep = new RegistryEndpoint(SCHEME_HTTP, HOST_LOCALHOST, server.getAddress().getPort(), null);
        try (RegistryClientImpl client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null)) {
            var ex = assertThrows(RuntimeException.class, () -> client.listTags(REPO, null, null));
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    void headBlobNotFound() throws Exception {
        // only HEAD returns 404
        startServerHeadOnly404();
        RegistryEndpoint ep = new RegistryEndpoint(SCHEME_HTTP, HOST_LOCALHOST, server.getAddress().getPort(), null);
        try (RegistryClientImpl client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null)) {
            assertTrue(client.headBlob(REPO, SHA_PREFIX + "missing").isEmpty());
        }
    }

    @Test
    void fetchBlobRangeReturnsPartialContent() throws Exception {
        byte[] layer = "0123456789".getBytes(StandardCharsets.UTF_8);
        String layerDigest = SHA_PREFIX + sha256(layer);
        Manifest manifest = manifest(layerDigest, layer.length);
        byte[] manifestBytes = new ObjectMapper().writeValueAsBytes(manifest);
        String manifestDigest = SHA_PREFIX + sha256(manifestBytes);

        startServerWithRange(layer, layerDigest, manifestBytes, manifestDigest);

        RegistryEndpoint ep = new RegistryEndpoint(SCHEME_HTTP, HOST_LOCALHOST, server.getAddress().getPort(), null);
        try (RegistryClientImpl client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null)) {
            File tmp = File.createTempFile("blob-range-", ".bin");
            tmp.deleteOnExit();
            BlobRequest req = new BlobRequest(REPO, layerDigest, null, OCTET,
                    new BlobRequest.RangeSpec(2L, 5L));

            BlobResult br = client.fetchBlob(req, tmp);

            assertEquals(4L, br.size());
            assertEquals(4L, tmp.length());
        }
    }

    @Test
    void fetchBlobRange416FallsBackToFull() throws Exception {
        byte[] layer = "0123456789".getBytes(StandardCharsets.UTF_8);
        String layerDigest = SHA_PREFIX + sha256(layer);
        Manifest manifest = manifest(layerDigest, layer.length);
        byte[] manifestBytes = new ObjectMapper().writeValueAsBytes(manifest);
        String manifestDigest = SHA_PREFIX + sha256(manifestBytes);

        startServerWithRange(layer, layerDigest, manifestBytes, manifestDigest);

        RegistryEndpoint ep = new RegistryEndpoint(SCHEME_HTTP, HOST_LOCALHOST, server.getAddress().getPort(), null);
        try (RegistryClientImpl client = new RegistryClientImpl(ep, new HttpClientConfig(), (CacheAdapter) null)) {
            File tmp = File.createTempFile("blob-range-416-", ".bin");
            tmp.deleteOnExit();
            BlobRequest req = new BlobRequest(REPO, layerDigest, null, OCTET,
                    new BlobRequest.RangeSpec(100L, 110L));

            BlobResult br = client.fetchBlob(req, tmp);

            assertEquals(layer.length, br.size());
            assertEquals(layer.length, tmp.length());
        }
    }

    private void startServer(byte[] layer,
                             String layerDigest,
                             byte[] manifestBytes,
                             String manifestDigest,
                             int tagsStatus,
                             int blobStatus) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(API_PREFIX, exchange -> respond(exchange, STATUS_OK, Map.of(), ""));
        server.createContext(API_PREFIX + REPO + "/manifests/latest", exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase(METHOD_GET)) {
                respond(exchange, STATUS_METHOD_NOT_ALLOWED, Map.of(), "");
                return;
            }
            Map<String, String> headers = Map.of(
                    CONTENT_TYPE, "application/vnd.docker.distribution.manifest.v2+json",
                    "Docker-Content-Digest", manifestDigest
            );
            respond(exchange, STATUS_OK, headers, manifestBytes);
        });
        server.createContext(API_PREFIX + REPO + "/blobs/" + layerDigest, exchange -> {
            if (METHOD_HEAD.equals(exchange.getRequestMethod())) {
                if (blobStatus == STATUS_NOT_FOUND) {
                    respond(exchange, STATUS_NOT_FOUND, Map.of(), new byte[0]);
                    return;
                }
                respond(exchange, STATUS_OK, Map.of(
                        CONTENT_LENGTH, String.valueOf(layer.length),
                        CONTENT_TYPE, OCTET
                ), new byte[0]);
                return;
            }
            if (METHOD_GET.equals(exchange.getRequestMethod())) {
                respond(exchange, blobStatus, Map.of(
                        CONTENT_LENGTH, String.valueOf(layer.length),
                        CONTENT_TYPE, OCTET
                ), layer);
                return;
            }
            respond(exchange, STATUS_METHOD_NOT_ALLOWED, Map.of(), new byte[0]);
        });
        server.createContext(API_PREFIX + REPO + "/tags/list", exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase(METHOD_GET)) {
                respond(exchange, STATUS_METHOD_NOT_ALLOWED, Map.of(), "");
                return;
            }
            String body = "{\"name\":\"" + REPO + "\",\"tags\":[\"latest\",\"edge\"]}";
            respond(exchange, tagsStatus, Map.of(CONTENT_TYPE, "application/json"), body);
        });
        server.start();
    }

    private void startServerHeadOnly404() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(API_PREFIX, exchange -> respond(exchange, STATUS_OK, Map.of(), ""));
        server.createContext(API_PREFIX + REPO + "/blobs/" + SHA_PREFIX + "missing",
                exchange -> respond(exchange, STATUS_NOT_FOUND, Map.of(), new byte[0]));
        server.start();
    }

    private void startServerWithRange(byte[] layer,
                                      String layerDigest,
                                      byte[] manifestBytes,
                                      String manifestDigest) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(API_PREFIX, exchange -> respond(exchange, STATUS_OK, Map.of(), ""));
        server.createContext(API_PREFIX + REPO + "/manifests/latest", exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase(METHOD_GET)) {
                respond(exchange, STATUS_METHOD_NOT_ALLOWED, Map.of(), "");
                return;
            }
            Map<String, String> headers = Map.of(
                    CONTENT_TYPE, "application/vnd.docker.distribution.manifest.v2+json",
                    "Docker-Content-Digest", manifestDigest
            );
            respond(exchange, STATUS_OK, headers, manifestBytes);
        });
        server.createContext(API_PREFIX + REPO + "/blobs/" + layerDigest, exchange -> {
            if (METHOD_HEAD.equals(exchange.getRequestMethod())) {
                respond(exchange, STATUS_OK, Map.of(
                        CONTENT_LENGTH, String.valueOf(layer.length),
                        CONTENT_TYPE, OCTET
                ), new byte[0]);
                return;
            }
            if (METHOD_GET.equals(exchange.getRequestMethod())) {
                String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
                if (rangeHeader != null) {
                    long[] range = parseRange(rangeHeader);
                    if (range.length != 2 || range[0] >= layer.length || range[1] < range[0]) {
                        respond(exchange, 416, Map.of(), new byte[0]);
                        return;
                    }
                    long start = range[0];
                    long end = Math.min(range[1], layer.length - 1);
                    byte[] part = Arrays.copyOfRange(layer, (int) start, (int) end + 1);
                    Map<String, String> headers = Map.of(
                            CONTENT_LENGTH, String.valueOf(part.length),
                            "Content-Range", "bytes %d-%d/%d".formatted(start, end, layer.length),
                            CONTENT_TYPE, OCTET
                    );
                    respond(exchange, 206, headers, part);
                    return;
                }
                respond(exchange, STATUS_OK, Map.of(
                        CONTENT_LENGTH, String.valueOf(layer.length),
                        CONTENT_TYPE, OCTET
                ), layer);
                return;
            }
            respond(exchange, STATUS_METHOD_NOT_ALLOWED, Map.of(), new byte[0]);
        });
        server.createContext(API_PREFIX + REPO + "/tags/list", exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase(METHOD_GET)) {
                respond(exchange, STATUS_METHOD_NOT_ALLOWED, Map.of(), "");
                return;
            }
            String body = "{\"name\":\"" + REPO + "\",\"tags\":[\"latest\",\"edge\"]}";
            respond(exchange, STATUS_OK, Map.of(CONTENT_TYPE, "application/json"), body);
        });
        server.start();
    }

    private static long[] parseRange(String raw) {
        if (raw == null || !raw.startsWith("bytes=")) {
            return new long[0];
        }
        String value = raw.substring("bytes=".length());
        String[] parts = value.split("-", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return new long[0];
        }
        try {
            long start = Long.parseLong(parts[0].trim());
            long end = Long.parseLong(parts[1].trim());
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return new long[0];
        }
    }

    private void respond(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        respond(exchange, status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, Map<String, String> headers, byte[] body)
            throws IOException {
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

