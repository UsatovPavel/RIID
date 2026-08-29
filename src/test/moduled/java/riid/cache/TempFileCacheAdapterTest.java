package riid.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import riid.cache.oci.CacheLease;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.CachePayload;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.TempFileCacheAdapter;
import riid.core.fs.HostFilesystem;
import riid.core.fs.HostFilesystemTestSupport;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.core.model.manifest.TestManifests;

class TempFileCacheAdapterTest {
    private TempFileCacheAdapter cache;
    private final HostFilesystem fs = HostFilesystemTestSupport.create();

    @AfterEach
    void tearDown() throws Exception {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void putAndAcquireRoundtrip() throws Exception {
        cache = new TempFileCacheAdapter(fs);
        ImageDigest digest = digest('a');
        Path payload = payload("hello");

        try (CacheLease stored = cache.put(digest,
                FilesystemCachePayload.of(fs, payload, fs.size(payload)), CacheMediaType.OCI_LAYER);
                CacheLease loaded = cache.acquire(digest).orElseThrow()) {
            assertEquals(stored.path(), loaded.path());
            assertEquals(fs.size(payload), loaded.entry().sizeBytes());
            assertTrue(fs.exists(loaded.path()));
        }
    }

    @Test
    void putComputesSizeWhenUnknown() throws Exception {
        cache = new TempFileCacheAdapter(fs);
        ImageDigest digest = digest('b');
        Path payload = payload("payload");
        CachePayload unknownSize = new CachePayload() {
            @Override
            public InputStream open() throws IOException {
                return fs.newInputStream(payload);
            }

            @Override
            public long sizeBytes() {
                return -1;
            }
        };

        try (CacheLease stored = cache.put(digest, unknownSize, CacheMediaType.UNKNOWN)) {
            assertEquals(fs.size(payload), stored.entry().sizeBytes());
        }
    }

    @Test
    void acquireMissingReturnsEmpty() {
        cache = new TempFileCacheAdapter(fs);
        assertTrue(cache.acquire(digest('c')).isEmpty());
    }

    @Test
    void cleanupIsIdempotent() throws Exception {
        HostFilesystem realFs = new NioHostFilesystem();
        cache = new TempFileCacheAdapter(realFs);
        Path root = cache.rootDir();

        cache.cleanup();
        cache.cleanup();

        assertFalse(realFs.exists(root));
    }

    @Test
    void putRejectsPayloadLargerThanCache() throws Exception {
        cache = boundedCache(4);
        ImageDigest digest = digest('d');
        Path payload = payload("12345");

        IOException error = assertThrows(IOException.class, () -> cache.put(digest,
                FilesystemCachePayload.of(fs, payload, fs.size(payload)), CacheMediaType.OCI_LAYER));

        assertTrue(error.getMessage().contains("maxCacheBytes"));
        assertTrue(cache.acquire(digest).isEmpty());
    }

    @Test
    void ninetyPercentEvictsLeastRecentlyUsedEntriesDownToFiftyPercent() throws Exception {
        cache = boundedCache(100);
        ImageDigest digestA = digest('a');
        ImageDigest digestB = digest('b');
        ImageDigest digestC = digest('c');
        ImageDigest digestD = digest('d');
        ImageDigest digestE = digest('e');
        putAndClose(digestA, 20);
        putAndClose(digestB, 20);
        putAndClose(digestC, 20);
        putAndClose(digestD, 20);
        try (CacheLease ignored = cache.acquire(digestA).orElseThrow()) {
            // Refresh A recency.
        }

        putAndClose(digestE, 10);

        assertPresent(digestA);
        assertMissing(digestB);
        assertMissing(digestC);
        assertPresent(digestD);
        assertPresent(digestE);
    }

    @Test
    void belowNinetyPercentDoesNotEvict() throws Exception {
        cache = boundedCache(100);
        ImageDigest[] digests = {digest('1'), digest('2'), digest('3')};
        putAndClose(digests[0], 30);
        putAndClose(digests[1], 30);
        putAndClose(digests[2], 29);

        for (ImageDigest digest : digests) {
            assertPresent(digest);
        }
    }

    @Test
    void sharedDigestRemainsUntilLastLeaseCloses() throws Exception {
        cache = boundedCache(100);
        ImageDigest shared = digest('6');
        CacheLease first = put(shared, 60);
        CacheLease second = cache.acquire(shared).orElseThrow();
        first.close();
        try (CacheLease pressure = put(digest('7'), 30)) {
            assertPresent(shared);
        }

        second.close();
        putAndClose(digest('8'), 1);

        assertMissing(shared);
    }

    @Test
    void cleanupFailsWithoutDeletingActiveLease() throws Exception {
        cache = boundedCache(100);
        CacheLease active = put(digest('9'), 10);
        Path path = active.path();

        IOException error = assertThrows(IOException.class, cache::cleanup);

        assertTrue(error.getMessage().contains("active"));
        assertTrue(fs.exists(path));
        active.close();
        cache.cleanup();
        assertFalse(fs.exists(cache.rootDir()));
    }

    @Test
    void concurrentPutOfSameDigestPublishesOnce() throws Exception {
        cache = boundedCache(100);
        ImageDigest digest = digest('f');
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch writerOpened = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CachePayload slowPayload = new CachePayload() {
            @Override
            public InputStream open() throws IOException {
                opens.incrementAndGet();
                writerOpened.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                return new ByteArrayInputStream("same".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public long sizeBytes() {
                return 4;
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CacheLease> first = executor.submit(
                    () -> cache.put(digest, slowPayload, CacheMediaType.OCI_LAYER));
            writerOpened.await();
            Future<CacheLease> second = executor.submit(
                    () -> cache.put(digest, slowPayload, CacheMediaType.OCI_LAYER));
            releaseWriter.countDown();
            try (CacheLease firstLease = first.get(); CacheLease secondLease = second.get()) {
                assertEquals(firstLease.path(), secondLease.path());
                assertTrue(fs.exists(firstLease.path()));
            }
        }

        assertEquals(1, opens.get());
    }

    @Test
    void waitingPutRetriesAfterConcurrentWriterFails() throws Exception {
        cache = boundedCache(100);
        ImageDigest digest = digest('0');
        CountDownLatch writerOpened = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CachePayload failingPayload = new CachePayload() {
            @Override
            public InputStream open() throws IOException {
                writerOpened.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                throw new IOException("expected write failure");
            }

            @Override
            public long sizeBytes() {
                return 4;
            }
        };
        Path successfulPayload = payload("good");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CacheLease> failing = executor.submit(
                    () -> cache.put(digest, failingPayload, CacheMediaType.OCI_LAYER));
            writerOpened.await();
            Future<CacheLease> retrying = executor.submit(() -> cache.put(digest,
                    FilesystemCachePayload.of(fs, successfulPayload, fs.size(successfulPayload)),
                    CacheMediaType.OCI_LAYER));
            releaseWriter.countDown();

            assertThrows(ExecutionException.class, failing::get);
            try (CacheLease stored = retrying.get()) {
                try (InputStream input = fs.newInputStream(stored.path())) {
                    assertEquals("good", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }

        assertPresent(digest);
    }

    private CacheLease put(ImageDigest digest, int size) throws Exception {
        Path payload = payload("x".repeat(size));
        return cache.put(digest, FilesystemCachePayload.of(fs, payload, fs.size(payload)),
                CacheMediaType.OCI_LAYER);
    }

    private TempFileCacheAdapter boundedCache(long maxBytes) {
        return new TempFileCacheAdapter(fs, maxBytes, 90, 50);
    }

    private void putAndClose(ImageDigest digest, int size) throws Exception {
        try (CacheLease ignored = put(digest, size)) {
            // Publish and immediately make the entry evictable.
        }
    }

    private Path payload(String content) throws Exception {
        Path payload = TestPaths.tempFile(fs, "cache-payload-", ".bin");
        fs.writeString(payload, content);
        return payload;
    }

    private void assertPresent(ImageDigest digest) {
        try (CacheLease ignored = cache.acquire(digest).orElseThrow()) {
            assertTrue(fs.exists(ignored.path()));
        }
    }

    private void assertMissing(ImageDigest digest) {
        assertTrue(cache.acquire(digest).isEmpty());
    }

    private static ImageDigest digest(char value) {
        return ImageDigest.parse(TestManifests.digest(value));
    }
}
