package riid.app.fs;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioHostFilesystemTest {

    @Test
    void atomicMoveFallsBackWhenAtomicNotSupported() throws Exception {
        Path zip = Files.createTempFile("nio-fs", ".zip");
        Files.deleteIfExists(zip);
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem jar = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path check = jar.getPath("check.txt");
            Files.writeString(check, "x");
            boolean atomicSupported;
            try {
                Files.move(check, jar.getPath("check-moved.txt"), StandardCopyOption.ATOMIC_MOVE);
                atomicSupported = true;
            } catch (AtomicMoveNotSupportedException e) {
                atomicSupported = false;
            }
            Assumptions.assumeFalse(atomicSupported, "Atomic move is supported on this FS");

            NioHostFilesystem fs = new NioHostFilesystem();
            Path source = jar.getPath("src.txt");
            Files.writeString(source, "data");
            Path target = jar.getPath("dst.txt");

            fs.atomicMove(source, target);

            assertFalse(Files.exists(source));
            assertTrue(Files.exists(target));
            try (var in = Files.newInputStream(target)) {
                assertEquals("data", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Files.deleteIfExists(zip);
    }
}

