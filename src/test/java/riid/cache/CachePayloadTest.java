package riid.cache;

import org.junit.jupiter.api.Test;
import riid.cache.oci.CachePayload;
import riid.cache.oci.FilesystemCachePayload;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

class CachePayloadTest {

    @Test
    void ofPathReturnsProvidedSizeAndData() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path tmp = TestPaths.tempFile(fs, "payload-", ".bin");
        byte[] bytes = "data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fs.write(tmp, bytes);

        CachePayload payload = FilesystemCachePayload.of(tmp, bytes.length);
        assertEquals(bytes.length, payload.sizeBytes());
        try (var in = payload.open()) {
            assertArrayEquals(bytes, in.readAllBytes());
        }
    }
}

