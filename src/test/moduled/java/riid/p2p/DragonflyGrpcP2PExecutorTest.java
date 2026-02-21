package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import DragonflyDfdaemon.v2.Dfdaemon;
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
        RecordingDownloaderFactory factory = new RecordingDownloaderFactory();

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        Optional<Path> result = executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);

        assertTrue(result.isEmpty());
        assertFalse(factory.createCalled, "downloader should not be created when disabled");
    }

    @Test
    void returnsPathWhenDownloadSucceeds() throws IOException {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", 5000, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        Path expectedPath = Path.of("/tmp/p2p-result.bin");
        RecordingDownloaderFactory factory = new RecordingDownloaderFactory(expectedPath);

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        Optional<Path> result = executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER);

        assertTrue(result.isPresent());
        assertEquals(expectedPath, result.get());
        assertTrue(factory.createCalled);
        assertTrue(factory.lastDownloader.closeCalled.get());
        assertEquals(DFDAEMON_ADDR, factory.lastAddr);
        String expectedUrl = "https://registry.example.com:5000/v2/" + REPO + "/blobs/" + DIGEST;
        assertEquals(expectedUrl, factory.lastDownloader.lastRequest.getDownload().getUrl());
        assertTrue(factory.lastDownloader.lastOutputPath.toString().contains("p2p-"),
                "output path should use p2p- prefix");
    }

    @Test
    void propagatesIOExceptionFromDownload() {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example.com", -1, null);
        HostFilesystem fs = new NioHostFilesystem();
        DragonflyConfig config = new DragonflyConfig(true, DFDAEMON_ADDR, null, null, null);
        RecordingDownloaderFactory factory = new RecordingDownloaderFactory(new IOException("dfdaemon unreachable"));

        DragonflyGrpcP2PExecutor executor = new DragonflyGrpcP2PExecutor(endpoint, fs, config, factory);

        IOException thrown = assertThrows(IOException.class, () ->
                executor.fetch(REPO, ImageDigest.parse(DIGEST), SIZE, CacheMediaType.OCI_LAYER));

        assertEquals("dfdaemon unreachable", thrown.getMessage());
        assertTrue(factory.createCalled);
        assertTrue(factory.lastDownloader.closeCalled.get());
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

    private static final class RecordingDownloaderFactory implements DfdaemonDownloaderFactory {
        final Path returnPath;
        final IOException throwOnDownload;
        boolean createCalled;
        String lastAddr;
        RecordingDfdaemonDownloader lastDownloader;

        RecordingDownloaderFactory() {
            this.returnPath = null;
            this.throwOnDownload = null;
        }

        RecordingDownloaderFactory(Path returnPath) {
            this.returnPath = returnPath;
            this.throwOnDownload = null;
        }

        RecordingDownloaderFactory(IOException throwOnDownload) {
            this.returnPath = null;
            this.throwOnDownload = throwOnDownload;
        }

        @Override
        public DfdaemonDownloader create(String dfdaemonAddr) {
            createCalled = true;
            lastAddr = dfdaemonAddr;
            lastDownloader = new RecordingDfdaemonDownloader(returnPath, throwOnDownload);
            return lastDownloader;
        }
    }

    private static final class RecordingDfdaemonDownloader implements DfdaemonDownloader {
        final Path returnPath;
        final IOException throwOnDownload;
        Dfdaemon.DownloadTaskRequest lastRequest;
        Path lastOutputPath;
        final AtomicBoolean closeCalled = new AtomicBoolean();

        RecordingDfdaemonDownloader(Path returnPath, IOException throwOnDownload) {
            this.returnPath = returnPath;
            this.throwOnDownload = throwOnDownload;
        }

        @Override
        public Path download(Dfdaemon.DownloadTaskRequest request, Path outputPath) throws IOException {
            lastRequest = request;
            lastOutputPath = outputPath;
            if (throwOnDownload != null) {
                throw throwOnDownload;
            }
            return returnPath != null ? returnPath : outputPath;
        }

        @Override
        public void close() {
            closeCalled.set(true);
        }
    }
}
