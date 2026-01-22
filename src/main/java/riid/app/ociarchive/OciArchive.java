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
    private final Path archiveFile;
    private final Path ociDirPath;

    public OciArchive(Path archivePath, Path ociDir) {
        this.archiveFile = Objects.requireNonNull(archivePath, "archivePath");
        this.ociDirPath = Objects.requireNonNull(ociDir, "ociDir");
    }

    public Path archivePath() {
        return archiveFile;
    }

    public Path ociDir() {
        return ociDirPath;
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(archiveFile);
        deleteRecursively(ociDirPath);
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

