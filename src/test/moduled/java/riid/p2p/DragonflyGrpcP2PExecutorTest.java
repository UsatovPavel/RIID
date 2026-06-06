package riid.p2p;

import riid.p2p.dragonfly.DragonflyConfig;
import riid.p2p.dragonfly.DragonflyGrpcP2PExecutor;
import riid.p2p.dragonfly.RegistryAuthProvider;
import ru.hse.dragonfly.puller.registry.RegistryAuth;
import ru.hse.dragonfly.puller.blobpuller.PullResult;
import ru.hse.dragonfly.puller.error.DragonflyPullErrorKind;
import ru.hse.dragonfly.puller.error.DragonflyPullException;
import ru.hse.dragonfly.puller.registry.RegistryPullRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragonflyGrpcP2PExecutorTest {
    private static final String HTTPS_SCHEME = "https";
    private static final String REGISTRY_HOST = "registry.example.com";
    private static final String REPO = "library/alpine";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final long SIZE = 1024;
    private static final String DFDAEMON_ADDR = "localhost:65001";

    @Test
    void returnsEmptyWhenDisabled() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(false, DFDAEMON_ADDR, null,
                null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory();

        Optional<Path> result;
        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint, config, factory)) {
            result = executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
        }

        assertTrue(result.isEmpty());
        assertFalse(factory.createCalled, "puller should not be created when disabled");
    }

    @Test
    void returnsPathWhenDownloadSucceeds() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, 5000, null);
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null,
                null, null);
        Path expectedPath = Path.of("/tmp/p2p-result.bin");
        RecordingPullerFactory factory = new RecordingPullerFactory(expectedPath);

        Optional<Path> result;
        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint, config, factory)) {
            result = executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
        }

        assertTrue(result.isPresent());
        assertEquals(expectedPath, result.get());
        assertTrue(factory.createCalled);
        assertEquals(1, factory.createCount);
        assertEquals("https://registry.example.com:5000", factory.lastPuller.lastRequest.registry());
        assertEquals(REPO, factory.lastPuller.lastRequest.repository());
        assertEquals(DIGEST, factory.lastPuller.lastRequest.digest());
        assertTrue(factory.lastPuller.lastRequest.outputPath().toString().contains("p2p-"),
                "output path should use p2p- prefix");
    }

    @Test
    void mapsBasicCredentialsToRegistryAuth() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, Credentials.basic("u", "p"));
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null,
                null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(Path.of("/tmp/p2p-result.bin"));

        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint, config, factory)) {
            executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
        }

        RegistryAuth.Basic auth = assertInstanceOf(RegistryAuth.Basic.class, factory.lastPuller.lastRequest.auth());
        assertEquals("u", auth.username());
        assertEquals("p", auth.password());
    }

    @Test
    void usesAuthProviderForRegistryPullRequestAuth() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(
                "https",
                "registry.example.com",
                -1,
                Credentials.basic("u", "p")
        );
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(Path.of("/tmp/p2p-result.bin"));
        RegistryAuthProvider authProvider = (ep, repository) -> RegistryAuth.bearer("df-token");

        riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint,
                config,
                authProvider,
                factory
        );
        executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);

        RegistryAuth.Bearer auth = assertInstanceOf(RegistryAuth.Bearer.class, factory.lastPuller.lastRequest.auth());
        assertEquals("df-token", auth.token());
    }

    @Test
    void propagatesIOExceptionFromDownload() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null,
                null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(
                new DragonflyPullException(DragonflyPullErrorKind.UNAVAILABLE, "dfdaemon unreachable"));

        IOException thrown;
        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint, config, factory)) {
            thrown = assertThrows(IOException.class,
                    () -> executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER));
        }

        assertTrue(thrown.getMessage().contains("dragonfly pull failed"));
        assertTrue(thrown.getMessage().contains("dfdaemon unreachable"));
        assertTrue(factory.createCalled);
    }

    @Test
    void reusesSinglePullerInstanceAcrossFetches() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null,
                null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(Path.of("/tmp/shared.bin"));
        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint, config, factory)) {
            executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
            executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
        }

        assertEquals(1, factory.createCount, "puller should be created once and reused");
    }

    @Test
    void closesSharedPullerAndCreatesNewOneAfterClose() throws Exception {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(Path.of("/tmp/shared.bin"));
        try (DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, config, factory)) {
            executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
            RecordingPuller firstPuller = factory.lastPuller;
            executor.close();
            assertThrows(IOException.class,
                    () -> executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER));
            assertEquals(1, firstPuller.closeCount, "shared puller must be closed once");
        }

        assertEquals(1, factory.createCount, "close should keep puller closed");
    }

    @Test
    void publishIsNoOp() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);
        riid.p2p.dragonfly.DragonflyConfig config = new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null,
                null, null);

        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                endpoint, config)) {
            executor.publish(ImageDigest.parse(DIGEST), Path.of("/tmp/x"), 100, CacheMediaType.OCI_LAYER);
        }
    }

    @Test
    void rejectsNullRepository() {
        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                new RegistryEndpoint(HTTPS_SCHEME, "x", -1, null),
                new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null, null, null))) {
            assertThrows(NullPointerException.class,
                    () -> executor.fetch(null, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejectsNullDigest() {
        try (riid.p2p.dragonfly.DragonflyGrpcP2PExecutor executor = new riid.p2p.dragonfly.DragonflyGrpcP2PExecutor(
                new RegistryEndpoint(HTTPS_SCHEME, "x", -1, null),
                new riid.p2p.dragonfly.DragonflyConfig(true, DFDAEMON_ADDR, null, null, null))) {
            assertThrows(NullPointerException.class, () -> executor.fetch(REPO, null, SIZE, CacheMediaType.OCI_LAYER));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class RecordingPullerFactory
            implements
                riid.p2p.dragonfly.DragonflyGrpcP2PExecutor.PullerFactory {
        final Path returnPath;
        final DragonflyPullException throwOnPull;
        boolean createCalled;
        int createCount;
        RecordingPuller lastPuller;

        RecordingPullerFactory() {
            this.returnPath = null;
            this.throwOnPull = null;
        }

        RecordingPullerFactory(Path returnPath) {
            this.returnPath = returnPath;
            this.throwOnPull = null;
        }

        RecordingPullerFactory(DragonflyPullException throwOnPull) {
            this.returnPath = null;
            this.throwOnPull = throwOnPull;
        }

        @Override
        public riid.p2p.dragonfly.DragonflyGrpcP2PExecutor.Puller create(DragonflyConfig config) {
            createCalled = true;
            createCount++;
            lastPuller = new RecordingPuller(returnPath, throwOnPull);
            return lastPuller;
        }
    }

    private static final class RecordingPuller implements DragonflyGrpcP2PExecutor.Puller {
        final Path returnPath;
        final DragonflyPullException throwOnPull;
        RegistryPullRequest lastRequest;
        int closeCount;

        RecordingPuller(Path returnPath, DragonflyPullException throwOnPull) {
            this.returnPath = returnPath;
            this.throwOnPull = throwOnPull;
        }

        @Override
        public CompletableFuture<PullResult> pull(RegistryPullRequest request) {
            lastRequest = request;
            if (throwOnPull != null) {
                return CompletableFuture.failedFuture(throwOnPull);
            }
            return CompletableFuture
                    .completedFuture(new PullResult(returnPath != null ? returnPath : request.outputPath()));
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
