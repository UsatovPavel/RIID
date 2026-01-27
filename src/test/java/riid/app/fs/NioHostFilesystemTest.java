package riid.app.fs;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioHostFilesystemTest {

    @Test
    void atomicMoveFallsBackWhenAtomicNotSupported() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path zip = TestPaths.tempFile(fs, "nio-fs", ".zip");
        fs.deleteIfExists(zip);
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem jar = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path check = jar.getPath("check.txt");
            fs.writeString(check, "x");
            Path source = jar.getPath("src.txt");
            fs.writeString(source, "data");
            Path target = jar.getPath("dst.txt");

            fs.atomicMove(source, target);

            assertFalse(fs.exists(source));
            assertTrue(fs.exists(target));
            try (var in = fs.newInputStream(target)) {
                assertEquals("data", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        fs.deleteIfExists(zip);
    }
}

