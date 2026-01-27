package riid.app.fs;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Test helper for creating unique paths via HostFilesystem.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestPaths {
    public static final Path DEFAULT_BASE_DIR = Path.of("build", "test-fs");

    private TestPaths() { }

    public static Path tempFile(HostFilesystem fs, String prefix, String suffix) throws IOException {
        Path path = PathSupport.tempPath(prefix, suffix);
        fs.createFile(path);
        return path;
    }

    public static Path tempFile(HostFilesystem fs, Path baseDir, String prefix, String suffix) throws IOException {
        Path path = PathSupport.tempPath(baseDir, prefix, suffix);
        fs.createFile(path);
        return path;
    }

    public static Path tempDir(HostFilesystem fs, String prefix) throws IOException {
        Path path = PathSupport.tempDirPath(prefix);
        fs.createDirectory(path);
        return path;
    }

    public static Path tempDir(HostFilesystem fs, Path baseDir, String prefix) throws IOException {
        Path path = PathSupport.tempDirPath(baseDir, prefix);
        fs.createDirectory(path);
        return path;
    }
}

