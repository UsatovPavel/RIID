package riid.app.fs;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Test helper for creating unique paths via HostFilesystem.
 */
public final class TestPaths {
    private TestPaths() { }

    public static Path tempFile(HostFilesystem fs, String prefix, String suffix) throws IOException {
        Path path = PathSupport.tempPath(prefix, suffix);
        fs.createFile(path);
        return path;
    }

    public static Path tempDir(HostFilesystem fs, String prefix) throws IOException {
        Path path = PathSupport.tempDirPath(prefix);
        fs.createDirectory(path);
        return path;
    }
}

