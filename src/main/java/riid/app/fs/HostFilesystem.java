package riid.app.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Filesystem abstraction for host operations (real or test).
 */
public interface HostFilesystem {
    Path createDirectory(Path dir) throws IOException;

    Path createFile(Path path) throws IOException;

    Path copy(Path source, Path target, CopyOption... options) throws IOException;

    Path write(Path path, byte[] bytes) throws IOException;

    Path writeString(Path path, String content) throws IOException;

    InputStream newInputStream(Path path) throws IOException;

    OutputStream newOutputStream(Path path) throws IOException;

    boolean exists(Path path);

    boolean isRegularFile(Path path);

    long size(Path path) throws IOException;

    String probeContentType(Path path) throws IOException;

    Stream<Path> walk(Path root) throws IOException;

    Path atomicMove(Path source, Path target) throws IOException;

    default Path atomicWrite(Path path, byte[] bytes) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bytes, "bytes");
        Path dir = path.toAbsolutePath().getParent();
        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath != null ? fileNamePath.toString() : "tmp";
        String prefix = fileName.length() < 3 ? "tmp-" + fileName : fileName;
        Path temp = PathSupport.tempPath(dir, prefix + "-", ".tmp");
        createFile(temp);
        try {
            write(temp, bytes);
            return atomicMove(temp, path);
        } catch (IOException e) {
            deleteIfExists(temp);
            throw e;
        }
    }

    default Path atomicWriteString(Path path, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        return atomicWrite(path, content.getBytes(StandardCharsets.UTF_8));
    }

    default void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    default void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

