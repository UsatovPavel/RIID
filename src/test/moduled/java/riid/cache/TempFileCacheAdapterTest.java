package riid.cache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.core.fs.HostFilesystem;
import riid.core.fs.HostFilesystemTestSupport;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.cache.oci.CacheEntry;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.CachePayload;
import riid.cache.oci.CachePin;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.TempFileCacheAdapter;

class TempFileCacheAdapterTest {
    private static final String SHA256_PREFIX = "sha256:";

    private TempFileCacheAdapter cache;
    private final HostFilesystem fs = HostFilesystemTestSupport.create();

    @AfterEach
    void tearDown() throws Exception {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void putAndGetRoundtrip() throws Exception {
        cache = new TempFileCacheAdapter(fs);
        ImageDigest digest = ImageDigest.parse(SHA256_PREFIX + "a".repeat(64));

        Path tmp = TestPaths.tempFile(fs, "cache-", ".bin");
        fs.writeString(tmp, "hello");

        CachePayload payload = FilesystemCachePayload.of(fs, tmp, fs.size(tmp));
        CacheEntry entry = cache.put(digest, payload, CacheMediaType.OCI_LAYER);

        assertTrue(cache.has(digest));
        var loaded = cache.get(digest).orElseThrow();
        Path loadedPath = cache.resolve(loaded.key()).orElseThrow();
        assertEquals(cache.resolve(entry.key()).orElseThrow(), loadedPath);
        // media type is derived from probeContentType; just ensure not null
        assertEquals(CacheMediaType.from(fs.probeContentType(loadedPath)), loaded.mediaType());
        assertEquals(fs.size(tmp), loaded.sizeBytes());
    }

    @Test
    void putComputesSizeWhenUnknown() throws Exception {
        cache = new TempFileCacheAdapter(fs);
        ImageDigest digest = ImageDigest.parse(SHA256_PREFIX + "b".repeat(64));

        Path tmp = TestPaths.tempFile(fs, "cache-", ".dat");
        fs.writeString(tmp, "payload");

        CachePayload payload = new CachePayload() {
            @Override
            public java.io.InputStream open() throws IOException {
                return fs.newInputStream(tmp);
            }

            @Override
            public long sizeBytes() {
                return -1;
            }
        };

        CacheEntry entry = cache.put(digest, payload, CacheMediaType.UNKNOWN);
        assertEquals(fs.size(tmp), entry.sizeBytes());
    }

    @Test
    void getMissingReturnsEmpty() {
        cache = new TempFileCacheAdapter(fs);
        ImageDigest digest = ImageDigest.parse(SHA256_PREFIX + "c".repeat(64));
        assertFalse(cache.has(digest));
        assertTrue(cache.get(digest).isEmpty());
    }

    @Tag("filesystem")
    @Test
    void cleanupIsIdempotent() throws Exception {
        HostFilesystem realFs = new NioHostFilesystem();
        cache = new TempFileCacheAdapter(realFs);
        Path root = cache.rootDir();
        if (realFs.exists(root)) {
            realFs.deleteRecursively(root);
        }
        cache.cleanup();
        cache.cleanup(); // should not throw
        assertFalse(realFs.exists(root));
    }

    @Test
    void putRejectsWhenCacheQuotaExceeded() throws Exception {
        cache = new TempFileCacheAdapter(fs, 4);
        ImageDigest digest = ImageDigest.parse(SHA256_PREFIX + "d".repeat(64));

        Path tmp = TestPaths.tempFile(fs, "cache-", ".bin");
        fs.writeString(tmp, "12345");

        CachePayload payload = FilesystemCachePayload.of(fs, tmp, fs.size(tmp));
        IOException error = assertThrows(IOException.class, () -> cache.put(digest, payload, CacheMediaType.OCI_LAYER));
        assertTrue(error.getMessage().contains("maxCacheBytes"));
        assertFalse(cache.has(digest));
    }

    @Test
    void putAtNinetyPercentEvictsLeastRecentlyUsedEntriesDownToFiftyPercent() throws Exception {
        cache = new TempFileCacheAdapter(fs, 100);
        ImageDigest digestA = digest('a');
        ImageDigest digestB = digest('b');
        ImageDigest digestC = digest('c');
        ImageDigest digestD = digest('d');
        ImageDigest digestE = digest('e');

        put(digestA, 20);
        put(digestB, 20);
        put(digestC, 20);
        put(digestD, 20);
        assertTrue(cache.get(digestA).isPresent(), "read must make A the most recently used entry");

        put(digestE, 10);

        assertTrue(cache.has(digestA), "recently read entry must survive LRU eviction");
        assertFalse(cache.has(digestB), "oldest entry must be evicted first");
        assertFalse(cache.has(digestC), "eviction must continue until the 50% low watermark");
        assertTrue(cache.has(digestD));
        assertTrue(cache.has(digestE), "new entry must not be evicted by its own put");
        assertEquals(50L, cachedBytes(digestA, digestD, digestE));
    }

    @Test
    void putBelowNinetyPercentDoesNotEvict() throws Exception {
        cache = new TempFileCacheAdapter(fs, 100);
        ImageDigest digestA = digest('1');
        ImageDigest digestB = digest('2');
        ImageDigest digestC = digest('3');
        ImageDigest digestD = digest('4');
        ImageDigest digestE = digest('5');

        put(digestA, 20);
        put(digestB, 20);
        put(digestC, 20);
        put(digestD, 20);
        put(digestE, 9);

        assertTrue(cache.has(digestA));
        assertTrue(cache.has(digestB));
        assertTrue(cache.has(digestC));
        assertTrue(cache.has(digestD));
        assertTrue(cache.has(digestE));
        assertEquals(89L, cachedBytes(digestA, digestB, digestC, digestD, digestE));
    }

    @Test
    void getRunsWhileAnotherDigestIsBeingWritten() throws Exception {
        cache = new TempFileCacheAdapter(fs, 100);
        ImageDigest cachedDigest = digest('a');
        ImageDigest writingDigest = digest('b');
        put(cachedDigest, 10);
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CacheEntry> writer = executor.submit(() -> cache.put(writingDigest,
                    blockingPayload(writeStarted, allowWrite), CacheMediaType.OCI_LAYER));
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS), "put must reach the payload read");

            Future<Optional<CacheEntry>> reader = executor.submit(() -> cache.get(cachedDigest));
            assertTrue(reader.get(2, TimeUnit.SECONDS).isPresent(), "cache hit must not wait for another digest put");

            allowWrite.countDown();
            assertEquals(writingDigest, writer.get(2, TimeUnit.SECONDS).digest());
        } finally {
            allowWrite.countDown();
        }
    }

    @Test
    void concurrentReadsAndPutsKeepLruStateConsistent() throws Exception {
        cache = new TempFileCacheAdapter(fs, 100);
        List<ImageDigest> digests = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            digests.add(digest(i));
        }
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (ImageDigest digest : digests) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    put(digest, 10);
                    return null;
                }));
            }
            for (int readerIndex = 0; readerIndex < 8; readerIndex++) {
                int offset = readerIndex;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 50; iteration++) {
                        ImageDigest digest = digests.get((iteration + offset) % digests.size());
                        cache.has(digest);
                        cache.get(digest);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        long totalBytes = cachedBytesIfPresent(digests);
        assertTrue(totalBytes > 0L);
        assertTrue(totalBytes < 90L, "bounded cache must finish below the high watermark");
    }

    @Test
    void cleanupWaitsForAnInFlightPutAndClosesTheCache() throws Exception {
        cache = new TempFileCacheAdapter(fs, 100);
        Path cacheRoot = cache.rootDir();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CacheEntry> writer = executor.submit(
                    () -> cache.put(digest('a'), blockingPayload(writeStarted, allowWrite), CacheMediaType.OCI_LAYER));
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS), "put must reach the payload read");

            Future<?> cleaner = executor.submit(() -> {
                cache.cleanup();
                return null;
            });
            assertThrows(TimeoutException.class, () -> cleaner.get(200, TimeUnit.MILLISECONDS),
                    "cleanup must wait while put owns the lifecycle read lock");

            allowWrite.countDown();
            writer.get(2, TimeUnit.SECONDS);
            cleaner.get(2, TimeUnit.SECONDS);
        } finally {
            allowWrite.countDown();
        }

        assertFalse(fs.exists(cacheRoot));
        assertThrows(IllegalStateException.class, () -> cache.has(digest('a')));
    }

    @Test
    void sharedDigestRemainsPinnedUntilTheLastPinCloses() throws Exception {
        cache = new TempFileCacheAdapter(fs, 100);
        ImageDigest sharedDigest = digest('a');
        put(sharedDigest, 60);

        try (CachePin firstImagePin = cache.pin(sharedDigest); CachePin secondImagePin = cache.pin(sharedDigest)) {
            firstImagePin.close();
            put(digest('b'), 30);
            assertTrue(cache.has(sharedDigest), "one remaining image pin must protect the shared blob");
        }

        put(digest('c'), 1);
        assertFalse(cache.has(sharedDigest), "blob becomes an LRU candidate after the last pin closes");
    }

    @Test
    void pinnedPressureLogsAndExposesPrometheusAlertAboveNinetyPercent() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        cache = new TempFileCacheAdapter(fs, 100, registry);
        Logger logger = (Logger) LoggerFactory.getLogger(TempFileCacheAdapter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        ImageDigest digestA = digest('a');
        ImageDigest digestB = digest('b');
        put(digestA, 60);

        try {
            try (CachePin pinA = cache.pin(digestA)) {
                put(digestB, 30);
                assertEquals(0.0D, registry.get("riid.cache.high.watermark.alert").gauge().value());
                try (CachePin pinB = cache.pin(digestB)) {
                    put(digest('c'), 1);

                    assertTrue(cache.has(digestA));
                    assertTrue(cache.has(digestB));
                    assertEquals(91.0D, registry.get("riid.cache.usage.bytes").gauge().value());
                    assertEquals(1.0D, registry.get("riid.cache.high.watermark.alert").gauge().value());
                    assertEquals(1.0D, registry.get("riid.cache.high.watermark.breaches").counter().count());
                    assertTrue(registry.scrape().contains("riid_cache_high_watermark_alert 1.0"));
                    assertTrue(registry.scrape().contains("riid_cache_high_watermark_breaches_total 1.0"));
                    assertTrue(hasLog(logs, Level.WARN, "stopped above low watermark"));
                    assertTrue(hasLog(logs, Level.ERROR, "remains above high watermark"));
                }
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        put(digest('d'), 1);
        assertEquals(0.0D, registry.get("riid.cache.high.watermark.alert").gauge().value());
    }

    private void put(ImageDigest digest, int size) throws Exception {
        Path payload = TestPaths.tempFile(fs, "cache-payload-", ".bin");
        fs.writeString(payload, "x".repeat(size));
        cache.put(digest, FilesystemCachePayload.of(fs, payload, fs.size(payload)), CacheMediaType.OCI_LAYER);
    }

    private long cachedBytes(ImageDigest... digests) throws Exception {
        long total = 0L;
        for (ImageDigest digest : digests) {
            CacheEntry entry = cache.get(digest).orElseThrow();
            total += fs.size(cache.resolve(entry.key()).orElseThrow());
        }
        return total;
    }

    private long cachedBytesIfPresent(List<ImageDigest> digests) throws Exception {
        long total = 0L;
        for (ImageDigest digest : digests) {
            Optional<CacheEntry> entry = cache.get(digest);
            if (entry.isPresent()) {
                total += fs.size(cache.resolve(entry.orElseThrow().key()).orElseThrow());
            }
        }
        return total;
    }

    private static CachePayload blockingPayload(CountDownLatch writeStarted, CountDownLatch allowWrite) {
        return new CachePayload() {
            @Override
            public InputStream open() {
                return new InputStream() {
                    private final InputStream delegate = new ByteArrayInputStream(
                            "0123456789".getBytes(StandardCharsets.US_ASCII));
                    private boolean started;

                    @Override
                    public int read() throws IOException {
                        awaitWritePermission();
                        return delegate.read();
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        awaitWritePermission();
                        return delegate.read(bytes, offset, length);
                    }

                    private void awaitWritePermission() throws IOException {
                        if (started) {
                            return;
                        }
                        started = true;
                        writeStarted.countDown();
                        try {
                            if (!allowWrite.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting to continue cache put");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting to continue cache put", e);
                        }
                    }
                };
            }

            @Override
            public long sizeBytes() {
                return 10L;
            }
        };
    }

    private static ImageDigest digest(char value) {
        return ImageDigest.parse(SHA256_PREFIX + String.valueOf(value).repeat(64));
    }

    private static ImageDigest digest(int value) {
        return ImageDigest.parse(SHA256_PREFIX + String.format("%064x", value));
    }

    private static boolean hasLog(ListAppender<ILoggingEvent> logs, Level level, String text) {
        return logs.list.stream()
                .anyMatch(event -> event.getLevel().equals(level) && event.getFormattedMessage().contains(text));
    }
}
