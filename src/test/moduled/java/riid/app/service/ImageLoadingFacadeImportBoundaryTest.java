package riid.app.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import riid.app.core.model.ImageId;
import riid.cache.oci.ImageDigest;
import riid.client.api.ManifestResult;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.core.logging.LogContextKeys;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.MediaType;
import riid.core.model.manifest.MediaTypes;
import riid.core.model.manifest.TestManifests;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.dispatcher.model.RepositoryName;
import riid.runtime.adapter.RuntimeAdapter;
import riid.runtime.adapter.RuntimeId;


@Tag("filesystem")
/**
 * Issue #75: engine.import must time the handover to the engine only. The
 * download is slow and the import instant here, so a timer that still spans
 * both is impossible to miss.
 */
class ImageLoadingFacadeImportBoundaryTest {

    private static final long DOWNLOAD_MS = 1000L;
    private static final String CLOSE = "close";
    private static final String NOT_USED = "not used";

    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    void engineImportExcludesTheDownload() throws Exception {
        Logger facadeLogger = (Logger) LoggerFactory.getLogger(ImageLoadingFacade.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        facadeLogger.addAppender(appender);

        SlowBlobs blobs = new SlowBlobs(fs);
        FastImportRuntime runtime = new FastImportRuntime();
        try (ImageLoadingFacade facade = new ImageLoadingFacade(blobs,
                new RuntimeRegistry(Map.of(RuntimeId.PODMAN, runtime)), noopClient(), fs,
                TestPaths.DEFAULT_BASE_DIR, List.of())) {
            facade.load(manifestResult(), runtime, imageId());
        }

        long engineImport = durationOf(appender, "engine.import");
        long loadTotal = durationOf(appender, "load.total");
        assertTrue(engineImport < DOWNLOAD_MS / 2,
                "engine.import must exclude the download, got " + engineImport + "ms");
        assertTrue(loadTotal >= DOWNLOAD_MS,
                "load.total must include the download, got " + loadTotal + "ms");
        facadeLogger.detachAppender(appender);
    }

    private static riid.client.api.RegistryClient noopClient() {
        return (riid.client.api.RegistryClient) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{riid.client.api.RegistryClient.class},
                (proxy, method, args) -> {
                    if (CLOSE.equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(NOT_USED);
                });
    }

    private static long durationOf(ListAppender<ILoggingEvent> appender, String event) {
        for (ILoggingEvent e : appender.list) {
            for (var kv : e.getKeyValuePairs() == null ? List.<org.slf4j.event.KeyValuePair>of() : e.getKeyValuePairs()) {
                if (LogContextKeys.EVENT.equals(kv.key) && event.equals(kv.value)) {
                    for (var d : e.getKeyValuePairs()) {
                        if (LogContextKeys.DURATION_MS.equals(d.key)) {
                            return ((Number) d.value).longValue();
                        }
                    }
                }
            }
        }
        throw new AssertionError("event not logged: " + event);
    }

    private static ImageId imageId() {
        return ImageId.fromRegistry("registry.example", "library/app", "latest");
    }

    private static ManifestResult manifestResult() {
        Descriptor config = TestManifests.config(TestManifests.digest('b'), 3);
        List<Descriptor> layers = List.of(TestManifests.gzipLayer(TestManifests.digest('1'), 3));
        Manifest manifest = TestManifests.manifest(config, layers);
        return new ManifestResult(TestManifests.digest('a'), MediaTypes.OCI_IMAGE_MANIFEST, 0L, manifest);
    }

    /** Slow on purpose: any timer that spans the download becomes obvious. */
    private static final class SlowBlobs implements RequestDispatcher {
        private final HostFilesystem fs;

        private SlowBlobs(HostFilesystem fs) {
            this.fs = fs;
        }

        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes,
                MediaType mediaType) {
            try {
                Thread.sleep(DOWNLOAD_MS);
                Path file = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "proof-", ".bin");
                fs.writeString(file, "blob " + digest.hex());
                return new FetchResult(digest, mediaType, file);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Classic path: no incremental support, import returns immediately. */
    private static final class FastImportRuntime implements RuntimeAdapter {
        private final List<Path> imported = new ArrayList<>();

        @Override
        public RuntimeId runtimeId() {
            return RuntimeId.PODMAN;
        }

        @Override
        public void importImage(Path imagePath) {
            imported.add(imagePath);
        }
    }
}
