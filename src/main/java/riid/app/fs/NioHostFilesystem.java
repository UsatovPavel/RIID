package riid.app.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * HostFilesystem implementation backed by java.nio.
 */
public final class NioHostFilesystem implements HostFilesystem {
    @Override
    public Path createDirectory(Path dir) throws IOException {
        return Files.createDirectories(dir);
    }

    @Override
    public Path createFile(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.createFile(path);
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

    @Override
    public InputStream newInputStream(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream newOutputStream(Path path) throws IOException {
        return Files.newOutputStream(path);
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    @Override
    public String probeContentType(Path path) throws IOException {
        return Files.probeContentType(path);
    }

    @Override
    public Stream<Path> walk(Path root) throws IOException {
        return Files.walk(root);
    }

    @Override
    public Path atomicMove(Path source, Path target) throws IOException {
        try {
            return Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

