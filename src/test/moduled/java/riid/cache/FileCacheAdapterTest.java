package riid.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import riid.cache.oci.CacheLease;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.CachePayload;
import riid.cache.oci.FileCacheAdapter;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.core.fs.HostFilesystem;
import riid.core.fs.HostFilesystemTestSupport;
import riid.core.fs.TestPaths;
import riid.core.model.manifest.TestManifests;

class FileCacheAdapterTest {

    private Path root;
    private FileCacheAdapter cache;
    private final HostFilesystem fs = HostFilesystemTestSupport.create();

    @AfterEach
    void tearDown() throws Exception {
        if (root != null) {
            fs.deleteRecursively(root);
        }
    }

    @Test
    void putAndGetRoundtrip() throws Exception {
        root = TestPaths.tempDir(fs, "file-cache-");
        cache = new FileCacheAdapter(root.toString(), fs);
        ImageDigest digest = ImageDigest.parse(TestManifests.digest('d'));

        Path tmp = TestPaths.tempFile(fs, "cache-file-", ".bin");
        fs.writeString(tmp, "hello");
        try (CacheLease stored = cache.put(digest, FilesystemCachePayload.of(fs, tmp, fs.size(tmp)),
                CacheMediaType.OCI_LAYER); CacheLease loaded = cache.acquire(digest).orElseThrow()) {
            assertEquals(stored.path(), loaded.path());
            assertEquals(fs.size(tmp), loaded.entry().sizeBytes());
        }
    }

    @Test
    void missingReturnsEmptyAndHasFalse() throws Exception {
        root = TestPaths.tempDir(fs, "file-cache-");
        cache = new FileCacheAdapter(root.toString(), fs);
        ImageDigest digest = ImageDigest.parse(TestManifests.digest('e'));
        assertTrue(cache.acquire(digest).isEmpty());
    }

    @Test
    void sizeIsComputedWhenUnknown() throws Exception {
        root = TestPaths.tempDir(fs, "file-cache-");
        cache = new FileCacheAdapter(root.toString());
        ImageDigest digest = ImageDigest.parse(TestManifests.digest('f'));

        Path tmp = TestPaths.tempFile(fs, "cache-file-", ".data");
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

        try (CacheLease stored = cache.put(digest, payload, CacheMediaType.UNKNOWN)) {
            assertEquals(fs.size(tmp), stored.entry().sizeBytes());
        }
    }
}
