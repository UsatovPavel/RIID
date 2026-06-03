package riid.app.ociarchive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import riid.core.fs.HostFilesystem;

/**
 * OCI layout workspace with optional tar file; {@link #close()} deletes the tar
 * (if any) and the layout tree.
 */
final class OciArchive implements AutoCloseable {
    private final Path archiveFile;
    private final Path ociDirPath;
    private final HostFilesystem fs;

    static OciArchive withTar(Path archivePath, Path ociDir, HostFilesystem fs) {
        return new OciArchive(Objects.requireNonNull(archivePath, "archivePath"), ociDir, fs);
    }

    /**
     * Layout only (tar is streamed to the runtime, not materialized as a file).
     */
    static OciArchive layoutOnly(Path ociDir, HostFilesystem fs) {
        return new OciArchive(null, ociDir, fs);
    }

    private OciArchive(Path archivePath, Path ociDir, HostFilesystem fs) {
        this.archiveFile = archivePath;
        this.ociDirPath = Objects.requireNonNull(ociDir, "ociDir");
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    Path archivePath() {
        if (archiveFile == null) {
            throw new IllegalStateException("layout-only workspace has no tar path");
        }
        return archiveFile;
    }

    Path ociDir() {
        return ociDirPath;
    }

    @Override
    public void close() throws IOException {
        if (archiveFile != null) {
            fs.deleteIfExists(archiveFile);
        }
        fs.deleteRecursively(ociDirPath);
    }
}
