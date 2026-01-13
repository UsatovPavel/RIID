package riid.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCacheAdapterTest {

    private Path root;
    private FileCacheAdapter cache;

    @AfterEach
    void tearDown() throws Exception {
        if (root != null) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void putAndGetRoundtrip() throws Exception {
        root = Files.createTempDirectory("file-cache");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse("sha256:" + "d".repeat(64));

        Path tmp = Files.createTempFile("cache-file-", ".bin");
        Files.writeString(tmp, "hello");
        CacheEntry entry = cache.put(
                digest,
                PathCachePayload.of(tmp, Files.size(tmp)),
                CacheMediaType.OCI_LAYER);

        assertTrue(cache.has(digest));
        CacheEntry loaded = cache.get(digest).orElseThrow();
        assertEquals(cache.resolve(entry.key()).orElseThrow(), cache.resolve(loaded.key()).orElseThrow());
        assertEquals(Files.size(tmp), loaded.sizeBytes());
    }

    @Test
    void missingReturnsEmptyAndHasFalse() throws Exception {
        root = Files.createTempDirectory("file-cache");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse("sha256:" + "e".repeat(64));
        assertFalse(cache.has(digest));
        assertTrue(cache.get(digest).isEmpty());
    }

    @Test
    void sizeIsComputedWhenUnknown() throws Exception {
        root = Files.createTempDirectory("file-cache");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse("sha256:" + "f".repeat(64));

        Path tmp = Files.createTempFile("cache-file-", ".data");
        Files.writeString(tmp, "payload");
        CachePayload payload = new CachePayload() {
            @Override
            public java.io.InputStream open() throws java.io.IOException {
                return Files.newInputStream(tmp);
            }

            @Override
            public long sizeBytes() {
                return -1;
            }
        };

        CacheEntry entry = cache.put(digest, payload, CacheMediaType.UNKNOWN);
        assertEquals(Files.size(tmp), entry.sizeBytes());
    }
}

