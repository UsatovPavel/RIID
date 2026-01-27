package riid.app.ociarchive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import riid.app.fs.HostFilesystem;

/**
 * OCI archive with cleanup support.
 */
final class OciArchive implements AutoCloseable {
    private final Path archiveFile;
    private final Path ociDirPath;
    private final HostFilesystem fs;

    public OciArchive(Path archivePath, Path ociDir, HostFilesystem fs) {
        this.archiveFile = Objects.requireNonNull(archivePath, "archivePath");
        this.ociDirPath = Objects.requireNonNull(ociDir, "ociDir");
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    public Path archivePath() {
        return archiveFile;
    }

    public Path ociDir() {
        return ociDirPath;
    }

    @Override
    public void close() throws IOException {
        fs.deleteIfExists(archiveFile);
        fs.deleteRecursively(ociDirPath);
    }
}

