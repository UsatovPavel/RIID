package riid.app.fs;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HostFilesystem implementation backed by java.nio.
 */
public final class NioHostFilesystem implements HostFilesystem {
    private final Path baseTempDir;

    public NioHostFilesystem(Path baseTempDir) {
        this.baseTempDir = baseTempDir;
    }

    @Override
    public Path createTempDirectory(String prefix) throws IOException {
        if (baseTempDir == null) {
            return Files.createTempDirectory(prefix);
        }
        return Files.createTempDirectory(baseTempDir, prefix);
    }

    @Override
    public Path createTempFile(String prefix, String suffix) throws IOException {
        if (baseTempDir == null) {
            return Files.createTempFile(prefix, suffix);
        }
        return Files.createTempFile(baseTempDir, prefix, suffix);
    }

    @Override
    public Path createDirectories(Path dir) throws IOException {
        return Files.createDirectories(dir);
    }

    @Override
    public Path copy(Path source, Path target, CopyOption... options) throws IOException {
        return Files.copy(source, target, options);
    }

    @Override
    public Path write(Path path, byte[] bytes) throws IOException {
        return Files.write(path, bytes);
    }

    @Override
    public Path writeString(Path path, String content) throws IOException {
        return Files.writeString(path, content);
    }
}

