package riid.integration.p2p;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.TagList;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.p2p.dragonfly.DragonflyConfig;
import riid.p2p.dragonfly.DragonflyGrpcP2PExecutor;

@Tag("local")
@Tag("filesystem")
class DragonflyGrpcP2PExecutorTest {
    private static final String REPO = "repo";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String MEDIA_LAYER = "application/vnd.oci.image.layer.v1.tar";
    private static final String V2_PATH_PREFIX = "/v2/";
    private static final String DFDAEMON_ADDR = System.getenv().getOrDefault("DFDAEMON_ADDR",
            "unix:///var/run/dragonfly/dfdaemon.sock");

    private HttpServer server;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void dispatcherUsesP2PWhenAvailable() throws Exception {
        ensureDfdaemonAvailable(DFDAEMON_ADDR);
        boolean useUnix = DFDAEMON_ADDR.startsWith("unix://");
        Assumptions.assumeTrue(useUnix || System.getenv("DFDAEMON_OUTPUT_DIR") != null,
                "for TCP set DFDAEMON_OUTPUT_DIR=/var/run/dragonfly/output");

        byte[] payload = "p2p-dispatcher".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(payload);
        AtomicInteger blobRequests = new AtomicInteger();
        String expectedAuthHeader = basicAuthHeader("riid-user", "riid-secret");
        startServer(payload, digest, blobRequests, expectedAuthHeader);

        RegistryEndpoint endpoint = new RegistryEndpoint("http", "127.0.0.1", server.getAddress().getPort(),
                Credentials.basic("riid-user", "riid-secret"));
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        try (DragonflyGrpcP2PExecutor p2p = new DragonflyGrpcP2PExecutor(endpoint, fs, config);
                TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
                RecordingRegistryClient registry = new RecordingRegistryClient(digest, payload.length, MEDIA_LAYER)) {
            var warmup = p2p.fetch(REPO, ImageDigest.parse(digest), payload.length, CacheMediaType.OCTET_STREAM);
            assertTrue(warmup.isPresent(), "warmup p2p fetch should succeed");
            int seedRequests = blobRequests.get();
            assertTrue(seedRequests > 0, "warmup should hit registry server at least once");

            SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(registry, cache, p2p, fs);
            FetchResult result = dispatcher.fetchImage(new ImageRef(REPO, "tag", null));

            assertNotNull(result);
            assertTrue(fs.exists(result.path()), "downloaded file should exist");
            assertEquals(payload.length, fs.size(result.path()), "downloaded size should match");
            assertEquals(1, registry.manifestCalls, "manifest should be fetched once");
            assertEquals(0, registry.blobCalls, "registry blob fetch should not be used when p2p hit");
            assertEquals(seedRequests, blobRequests.get(),
                    "dispatcher should not hit registry server for blob after warmup");
        }
    }

    private void startServer(byte[] payload, String digest, AtomicInteger blobRequests, String expectedAuthHeader)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(V2_PATH_PREFIX, exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
            }
        });
        server.createContext(V2_PATH_PREFIX + REPO + "/blobs/" + digest, exchange -> {
            try (exchange) {
                if (blobRequests != null) {
                    blobRequests.incrementAndGet();
                }
                if (expectedAuthHeader != null) {
                    assertEquals(expectedAuthHeader, exchange.getRequestHeaders().getFirst("Authorization"),
                            "dfdaemon should pass registry auth from RegistryPullRequest");
                }
                exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE);
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(payload.length));
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
        });
        server.start();
    }

    /**
     * Skips test if dfdaemon is not available. For unix socket: skip when missing
     * (single mode not running). For TCP: skip when unreachable (local multi-node
     * env not running).
     */
    private static void ensureDfdaemonAvailable(String dfdaemonAddr) {
        if (dfdaemonAddr == null || dfdaemonAddr.isBlank()) {
            return;
        }
        if (dfdaemonAddr.startsWith("unix://")) {
            String path = dfdaemonAddr.substring(7).trim();
            Assumptions.assumeTrue(Files.exists(Path.of(path)),
                    () -> "dfdaemon socket not found at " + path + " (run ./minikube-dragonfly.sh)");
        } else {
            int colon = dfdaemonAddr.lastIndexOf(':');
            if (colon > 0) {
                String host = dfdaemonAddr.substring(0, colon).trim();
                int port = Integer.parseInt(dfdaemonAddr.substring(colon + 1).trim());
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), 2000);
                } catch (IOException e) {
                    Assumptions.assumeTrue(false, () -> "dfdaemon gRPC not reachable at " + dfdaemonAddr
                            + " (start local multi-node Dragonfly env)");
                }
            }
        }
    }

    private static String basicAuthHeader(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    /**
     * Registry stub: returns a manifest with one layer and fails on blob fetch.
     */
    private static final class RecordingRegistryClient implements RegistryClient {
        private final String digest;
        private final long size;
        private final String mediaType;
        int manifestCalls;
        int blobCalls;

        private RecordingRegistryClient(String digest, long size, String mediaType) {
            this.digest = digest;
            this.size = size;
            this.mediaType = mediaType;
        }

        @Override
        public ManifestResult fetchManifest(String repository, String reference) {
            manifestCalls++;
            Descriptor layer = new Descriptor(mediaType, digest, size);
            Manifest manifest = new Manifest(2, "application/vnd.oci.image.manifest.v1+json",
                    new Descriptor("application/json", digest, 1), List.of(layer));
            return new ManifestResult(digest, manifest.mediaType(), size, manifest);
        }

        @Override
        public BlobResult fetchConfig(String repository, Manifest manifest, java.io.File target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlobResult fetchBlob(BlobRequest request, java.io.File target) {
            blobCalls++;
            throw new AssertionError("registry blob fetch should not be called");
        }

        @Override
        public Optional<Long> headBlob(String repository, String digest) {
            return Optional.empty();
        }

        @Override
        public TagList listTags(String repository, Integer n, String last) {
            return new TagList(repository, List.of());
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
