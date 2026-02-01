package riid.app.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class HostFilesystemAtomicWriteTest {

    @Test
    void atomicWriteStringWritesContent() throws Exception {
        HostFilesystem fs = HostFilesystemTestSupport.create();
        Path target = Path.of("mem", "file.txt");

        fs.atomicWriteString(target, "hello");

        try (var in = fs.newInputStream(target)) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void atomicWriteCleansTempOnFailure() {
        class FailingMoveFs extends InMemoryHostFilesystem {
            Path lastTemp;

            @Override
            public Path createFile(Path path) throws IOException {
                if (path.toString().endsWith(".tmp")) {
                    lastTemp = path;
                }
                return super.createFile(path);
            }

            @Override
            public Path atomicMove(Path source, Path target) throws IOException {
                throw new IOException("boom");
            }
        }

        FailingMoveFs fs = new FailingMoveFs();
        Path target = Path.of("mem", "target.txt");

        var ex = assertThrows(IOException.class, () -> fs.atomicWriteString(target, "data"));
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getMessage());
        assertFalse(fs.exists(fs.lastTemp));
    }
}

