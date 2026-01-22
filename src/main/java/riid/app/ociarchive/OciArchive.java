package riid.app.ociarchive;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * OCI archive with cleanup support.
 */
public final class OciArchive implements AutoCloseable {
    private final Path archivePath;
    private final Path ociDir;

    public OciArchive(Path archivePath, Path ociDir) {
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.ociDir = Objects.requireNonNull(ociDir, "ociDir");
    }

    public Path archivePath() {
        return archivePath;
    }

    public Path ociDir() {
        return ociDir;
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(archivePath);
        deleteRecursively(ociDir);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

