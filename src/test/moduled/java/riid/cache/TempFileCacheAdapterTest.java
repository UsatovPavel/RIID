package riid.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.cache.oci.CacheEntry;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.CachePayload;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.TempFileCacheAdapter;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.core.fs.HostFilesystem;
import riid.core.fs.HostFilesystemTestSupport;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;

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

    private static ImageDigest digest(char value) {
        return ImageDigest.parse(SHA256_PREFIX + String.valueOf(value).repeat(64));
    }
}
