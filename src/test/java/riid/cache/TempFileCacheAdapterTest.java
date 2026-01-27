package riid.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

class TempFileCacheAdapterTest {

    private TempFileCacheAdapter cache;
    private final HostFilesystem fs = new NioHostFilesystem();

    @AfterEach
    void tearDown() throws Exception {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void putAndGetRoundtrip() throws Exception {
        cache = new TempFileCacheAdapter();
        ImageDigest digest = ImageDigest.parse("sha256:" + "a".repeat(64));

        Path tmp = TestPaths.tempFile(fs, "cache-", ".bin");
        fs.writeString(tmp, "hello");

        CachePayload payload = FilesystemCachePayload.of(tmp, fs.size(tmp));
        CacheEntry entry = cache.put(digest, payload, CacheMediaType.OCI_LAYER);

        assertTrue(cache.has(digest));
        var loaded = cache.get(digest).orElseThrow();
        Path loadedPath = cache.resolve(loaded.key()).orElseThrow();
        assertEquals(cache.resolve(entry.key()).orElseThrow(), loadedPath);
        // media type is derived from probeContentType; just ensure not null
        assertEquals(
                CacheMediaType.from(fs.probeContentType(loadedPath)),
                loaded.mediaType());
        assertEquals(fs.size(tmp), loaded.sizeBytes());
    }

    @Test
    void putComputesSizeWhenUnknown() throws Exception {
        cache = new TempFileCacheAdapter();
        ImageDigest digest = ImageDigest.parse("sha256:" + "b".repeat(64));

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
        cache = new TempFileCacheAdapter();
        ImageDigest digest = ImageDigest.parse("sha256:" + "c".repeat(64));
        assertFalse(cache.has(digest));
        assertTrue(cache.get(digest).isEmpty());
    }

    @Test
    void cleanupIsIdempotent() throws Exception {
        cache = new TempFileCacheAdapter();
        Path root = cache.rootDir();
        cache.cleanup();
        cache.cleanup(); // should not throw
        assertFalse(fs.exists(root));
    }
}

