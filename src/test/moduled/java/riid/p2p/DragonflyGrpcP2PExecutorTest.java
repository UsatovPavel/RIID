package riid.p2p;

import hse.ru.dragonfly.puller.error.DragonflyPullException;
import hse.ru.dragonfly.puller.error.ErrorKind;
import hse.ru.dragonfly.puller.model.PullRequest;
import hse.ru.dragonfly.puller.model.PullResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragonflyGrpcP2PExecutorTest {

    private static final String REPO = "library/alpine";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final long SIZE = 1024;
    private static final String DFDAEMON_ADDR = "localhost:65001";

    @Test
    void returnsEmptyWhenDisabled() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", -1, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(false, DFDAEMON_ADDR, null, null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory();

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        Optional<Path> result = executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);

        assertTrue(result.isEmpty());
        assertFalse(factory.createCalled, "puller should not be created when disabled");
    }

    @Test
    void returnsPathWhenDownloadSucceeds() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", 5000, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        Path expectedPath = Path.of("/tmp/p2p-result.bin");
        RecordingPullerFactory factory = new RecordingPullerFactory(expectedPath);

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        Optional<Path> result = executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);

        assertTrue(result.isPresent());
        assertEquals(expectedPath, result.get());
        assertTrue(factory.createCalled);
        assertEquals(1, factory.createCount);
        String expectedUrl = "https://registry.example.com:5000/v2/" + REPO + "/blobs/" + DIGEST;
        assertEquals(expectedUrl, factory.lastPuller.lastRequest.blobUrl());
        assertTrue(factory.lastPuller.lastRequest.outputPath().toString().contains("p2p-"),
                "output path should use p2p- prefix");
    }

    @Test
    void propagatesIOExceptionFromDownload() {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", -1, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(
                new DragonflyPullException(ErrorKind.UNAVAILABLE, "dfdaemon unreachable"));

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        IOException thrown = assertThrows(IOException.class, () ->
                executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER));

        assertTrue(thrown.getMessage().contains("dragonfly pull failed"));
        assertTrue(thrown.getMessage().contains("dfdaemon unreachable"));
        assertTrue(factory.createCalled);
    }

    @Test
    void reusesSinglePullerInstanceAcrossFetches() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", -1, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        RecordingPullerFactory factory = new RecordingPullerFactory(Path.of("/tmp/shared.bin"));
        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);
        executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);

        assertEquals(1, factory.createCount, "puller should be created once and reused");
    }

    @Test
    void publishIsNoOp() {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", -1, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config);

        executor.publish(ImageDigest.parse(DIGEST), Path.of("/tmp/x"), 100, CacheMediaType.OCI_LAYER);
    }

    @Test
    void rejectsNullRepository() {
        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(
                new RegistryEndpoint("https", "x", -1, null),
                new NioHostFilesystem(),
                new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null));

        assertThrows(NullPointerException.class, () ->
                executor.fetch(null, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER));
    }

    @Test
    void rejectsNullDigest() {
        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(
                new RegistryEndpoint("https", "x", -1, null),
                new NioHostFilesystem(),
                new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null));

        assertThrows(NullPointerException.class, () ->
                executor.fetch(REPO, null, SIZE, CacheMediaType.OCI_LAYER));
    }

    private static final class RecordingPullerFactory implements DragonflyGrpcP2PExecutor.PullerFactory {
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
        public DragonflyGrpcP2PExecutor.Puller create(DragonflyConfig config) {
            createCalled = true;
            createCount++;
            lastPuller = new RecordingPuller(returnPath, throwOnPull);
            return lastPuller;
        }
    }

    private static final class RecordingPuller implements DragonflyGrpcP2PExecutor.Puller {
        final Path returnPath;
        final DragonflyPullException throwOnPull;
        PullRequest lastRequest;

        RecordingPuller(Path returnPath, DragonflyPullException throwOnPull) {
            this.returnPath = returnPath;
            this.throwOnPull = throwOnPull;
        }

        @Override
        public PullResult pull(PullRequest request) throws DragonflyPullException {
            lastRequest = request;
            if (throwOnPull != null) {
                throw throwOnPull;
            }
            return new PullResult(returnPath != null ? returnPath : request.outputPath());
        }
    }
}
