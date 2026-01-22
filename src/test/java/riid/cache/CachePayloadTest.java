package riid.cache;

import org.junit.jupiter.api.Test;
import riid.cache.oci.CachePayload;
import riid.cache.oci.FilesystemCachePayload;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CachePayloadTest {

    @Test
    void ofPathReturnsProvidedSizeAndData() throws Exception {
        Path tmp = Files.createTempFile("payload-", ".bin");
        byte[] bytes = "data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(tmp, bytes);

        CachePayload payload = FilesystemCachePayload.of(tmp, bytes.length);
        assertEquals(bytes.length, payload.sizeBytes());
        try (var in = payload.open()) {
            assertArrayEquals(bytes, in.readAllBytes());
        }
    }
}

