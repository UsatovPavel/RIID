package riid.integration.p2p;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import riid.client.core.config.RegistryEndpoint;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.TagList;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.p2p.config.DragonflyConfig;
import riid.p2p.DragonflyP2PExecutor;

@Tag("local")
@Tag("filesystem")
class DragonflyP2PExecutorTest {
    private static final String REPO = "repo";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String MEDIA_LAYER = "application/vnd.oci.image.layer.v1.tar";
    private static final String JAVA_IO_TMPDIR = "java.io.tmpdir";
    private static final String V2_PATH_PREFIX = "/v2/";
    private static final String DFDAEMON_ENDPOINT = "/tmp/dfdaemon.sock";

    private HttpServer server;

    @AfterEach
    @SuppressWarnings("unused")
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesBlobViaDfgetAndCaches() throws Exception {
        DfgetEnv dfgetEnv = dfgetEnv();
        ensureDfgetAvailable(dfgetEnv.path());

        byte[] payload = "p2p-test-payload".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(payload);
        startServer(payload, digest);

        RegistryEndpoint endpoint = new RegistryEndpoint("http", dfgetEnv.host(), server.getAddress().getPort(), null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, dfgetEnv.path(), null, null, null, DFDAEMON_ENDPOINT);

        try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs)) {
            DragonflyP2PExecutor p2p = new DragonflyP2PExecutor(endpoint, cache, fs, config);

            var result = p2p.fetch(REPO, ImageDigest.parse(digest), payload.length, CacheMediaType.OCTET_STREAM);

            assertTrue(result.isPresent(), "dfget result should be present");
            Path path = result.get();
            assertNotNull(path);
            assertTrue(fs.exists(path), "downloaded file should exist");
            assertEquals(payload.length, fs.size(path), "downloaded size should match");
            assertTrue(cache.has(ImageDigest.parse(digest)), "cache should contain fetched layer");
        }
    }

    @Test
    void fetchesBlobViaMultiNodeP2P() throws Exception {
        DfgetEnv dfgetEnv = dfgetEnv();
        ensureDfgetAvailable(dfgetEnv.path());

        byte[] payload = "p2p-test-multi".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(payload);
        startServer(payload, digest);

        RegistryEndpoint endpoint = new RegistryEndpoint("http", dfgetEnv.host(), server.getAddress().getPort(), null);
        HostFilesystem fs = new NioHostFilesystem();
        String previousTmp = System.getProperty(JAVA_IO_TMPDIR);
        System.setProperty(JAVA_IO_TMPDIR, "/tmp");
        try {
            String seedDfget = createDfgetWrapper(dfgetEnv.path(), "dfdaemon1", true);
            Path seedPath = Files.createTempFile("dfget-seed-", ".bin");
            seedPath.toFile().deleteOnExit();
            String seedUrl = endpoint.uri(V2_PATH_PREFIX + REPO + "/blobs/" + digest).toString();
            runDfget(seedDfget, seedUrl, seedPath);
            assertTrue(Files.exists(seedPath), "seed file should exist");
            assertTrue(Files.size(seedPath) > 0, "seed file should not be empty");

            DragonflyConfig config = new DragonflyConfig(
                    true,
                    createDfgetWrapper(dfgetEnv.path(), "dfdaemon2", true),
                    null,
                    null,
                    null,
                    DFDAEMON_ENDPOINT);
            try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs)) {
                DragonflyP2PExecutor p2p = new DragonflyP2PExecutor(endpoint, cache, fs, config);
                var result = p2p.fetch(REPO, ImageDigest.parse(digest), payload.length, CacheMediaType.OCTET_STREAM);

                assertTrue(result.isPresent(), "dfget result should be present");
                Path path = result.get();
                assertNotNull(path);
                assertTrue(fs.exists(path), "downloaded file should exist");
                assertEquals(payload.length, fs.size(path), "downloaded size should match");
            }
        } finally {
            if (previousTmp != null) {
                System.setProperty(JAVA_IO_TMPDIR, previousTmp);
            }
        }
    }

    @Test
    void dispatcherUsesP2PWhenAvailable() throws Exception {
        DfgetEnv dfgetEnv = dfgetEnv();
        ensureDfgetAvailable(dfgetEnv.path());

        byte[] payload = "p2p-dispatcher".getBytes(StandardCharsets.UTF_8);
        String digest = "sha256:" + sha256(payload);
        AtomicInteger blobRequests = new AtomicInteger();
        startServer(payload, digest, blobRequests);

        RegistryEndpoint endpoint = new RegistryEndpoint("http", dfgetEnv.host(), server.getAddress().getPort(), null);
        HostFilesystem fs = new NioHostFilesystem();
        String previousTmp = System.getProperty(JAVA_IO_TMPDIR);
        System.setProperty(JAVA_IO_TMPDIR, "/tmp");
        try {
            String seedDfget = createDfgetWrapper(dfgetEnv.path(), "dfdaemon1", true);
            Path seedPath = Files.createTempFile("dfget-seed-", ".bin");
            seedPath.toFile().deleteOnExit();
            String seedUrl = endpoint.uri(V2_PATH_PREFIX + REPO + "/blobs/" + digest).toString();
            runDfget(seedDfget, seedUrl, seedPath);

            int seedRequests = blobRequests.get();
            assertTrue(seedRequests > 0, "seed should hit registry server");

            DragonflyConfig config = new DragonflyConfig(
                    true,
                    createDfgetWrapper(dfgetEnv.path(), "dfdaemon2", true),
                    null,
                    null,
                    null,
                    DFDAEMON_ENDPOINT);
            try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
                 RecordingRegistryClient registry = new RecordingRegistryClient(digest, payload.length, MEDIA_LAYER)) {
                DragonflyP2PExecutor p2p = new DragonflyP2PExecutor(endpoint, cache, fs, config);
                SimpleRequestDispatcher dispatcher = new SimpleRequestDispatcher(registry, cache, p2p, fs);
                FetchResult result = dispatcher.fetchImage(new ImageRef(REPO, "tag", null));

                assertNotNull(result);
                assertTrue(fs.exists(result.path()), "downloaded file should exist");
                assertEquals(payload.length, fs.size(result.path()), "downloaded size should match");
                assertEquals(1, registry.manifestCalls, "manifest should be fetched once");
                assertEquals(0, registry.blobCalls, "registry blob fetch should not be used when p2p hit");
                assertEquals(seedRequests, blobRequests.get(), "dispatcher should not hit registry server for blob");
            }
        } finally {
            if (previousTmp != null) {
                System.setProperty(JAVA_IO_TMPDIR, previousTmp);
            }
        }
    }

    private void startServer(byte[] payload, String digest) throws IOException {
        startServer(payload, digest, null);
    }

    private void startServer(byte[] payload, String digest, AtomicInteger blobRequests) throws IOException {
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

    private static DfgetEnv dfgetEnv() {
        String env = System.getenv("DFGET_PATH");
        if (env != null && !env.isBlank()) {
            return new DfgetEnv(env, "localhost");
        }
        var resource = DragonflyP2PExecutorTest.class.getResource("/dfget.sh");
        if (resource != null) {
            return new DfgetEnv(extractDfgetScript(), resolveDockerGateway());
        }
        return new DfgetEnv("dfget", "localhost");
    }

    private record DfgetEnv(String path, String host) {
    }

    private static String extractDfgetScript() {
        try (var in = DragonflyP2PExecutorTest.class.getResourceAsStream("/dfget.sh")) {
            if (in == null) {
                return "dfget";
            }
            Path tmp = java.nio.file.Files.createTempFile("dfget-", ".sh");
            java.nio.file.Files.write(tmp, in.readAllBytes());
            tmp.toFile().setExecutable(true, false);
            return tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            return "dfget";
        }
    }

    private static String createDfgetWrapper(String basePath, String container, boolean directMount) {
        try {
            Path tmp = Files.createTempFile("dfget-wrapper-", ".sh");
            String direct = directMount ? "1" : "";
            String script = """
                    #!/usr/bin/env bash
                    export DFGET_CONTAINER="%s"
                    export DFGET_DIRECT_MOUNT="%s"
                    exec "%s" "$@"
                    """.formatted(container, direct, basePath);
            Files.writeString(tmp, script);
            tmp.toFile().setExecutable(true, false);
            return tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            return basePath;
        }
    }

    private static String resolveDockerGateway() {
        String gateway = inspectDockerNetworkGateway("dragonfly-net");
        if (gateway != null) {
            return gateway;
        }
        gateway = inspectContainerGateway("dfdaemon1");
        if (gateway != null) {
            return gateway;
        }
        return "host.docker.internal";
    }

    private static String inspectDockerNetworkGateway(String networkName) {
        try {
            Process process = new ProcessBuilder(
                    "docker",
                    "network",
                    "inspect",
                    "-f",
                    "{{(index .IPAM.Config 0).Gateway}}",
                    networkName)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            if (code == 0) {
                String text = new String(output, StandardCharsets.UTF_8).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static String inspectContainerGateway(String containerName) {
        try {
            Process process = new ProcessBuilder(
                    "docker",
                    "inspect",
                    "-f",
                    "{{range $k,$v := .NetworkSettings.Networks}}{{println $v.Gateway}}{{end}}",
                    containerName)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            if (code == 0) {
                String text = new String(output, StandardCharsets.UTF_8).trim();
                if (!text.isBlank()) {
                    return text.split("\\s+")[0];
                }
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static void ensureDfgetAvailable(String dfgetPath) {
        try {
            Process process = new ProcessBuilder(dfgetPath, "--help")
                    .redirectErrorStream(true)
                    .start();
            int code = process.waitFor();
            assertTrue(code == 0, "dfget is not available");
        } catch (IOException e) {
            throw new AssertionError("dfget is not available", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("dfget is not available", e);
        }
    }

    private static void runDfget(String dfgetPath, String url, Path out) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                dfgetPath,
                "-e",
                DFDAEMON_ENDPOINT,
                "-O",
                out.toAbsolutePath().toString(),
                url,
                "--console")
                .redirectErrorStream(true)
                .start();
        int code = process.waitFor();
        assertTrue(code == 0, "dfget seed failed");
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
