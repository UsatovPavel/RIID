package riid.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

class FileCacheAdapterTest {

    private Path root;
    private FileCacheAdapter cache;
    private final HostFilesystem fs = new NioHostFilesystem(null);

    @AfterEach
    void tearDown() throws Exception {
        if (root != null) {
            fs.deleteRecursively(root);
        }
    }

    @Test
    void putAndGetRoundtrip() throws Exception {
        root = fs.createTempDirectory("file-cache");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse("sha256:" + "d".repeat(64));

        Path tmp = fs.createTempFile("cache-file-", ".bin");
        fs.writeString(tmp, "hello");
        CacheEntry entry = cache.put(
                digest,
                FilesystemCachePayload.of(tmp, fs.size(tmp)),
                CacheMediaType.OCI_LAYER);

        assertTrue(cache.has(digest));
        CacheEntry loaded = cache.get(digest).orElseThrow();
        assertEquals(cache.resolve(entry.key()).orElseThrow(), cache.resolve(loaded.key()).orElseThrow());
        assertEquals(fs.size(tmp), loaded.sizeBytes());
    }

    @Test
    void missingReturnsEmptyAndHasFalse() throws Exception {
        root = fs.createTempDirectory("file-cache");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse("sha256:" + "e".repeat(64));
        assertFalse(cache.has(digest));
        assertTrue(cache.get(digest).isEmpty());
    }

    @Test
    void sizeIsComputedWhenUnknown() throws Exception {
        root = fs.createTempDirectory("file-cache");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse("sha256:" + "f".repeat(64));

        Path tmp = fs.createTempFile("cache-file-", ".data");
        fs.writeString(tmp, "payload");
        CachePayload payload = new CachePayload() {
            @Override
            public java.io.InputStream open() throws java.io.IOException {
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
}

