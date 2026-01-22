package riid.app.fs;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Path;

/**
 * Filesystem abstraction for host operations (real or test).
 */
public interface HostFilesystem {
    Path createTempDirectory(String prefix) throws IOException;

    Path createTempFile(String prefix, String suffix) throws IOException;

    Path createDirectories(Path dir) throws IOException;

    Path copy(Path source, Path target, CopyOption... options) throws IOException;

    Path write(Path path, byte[] bytes) throws IOException;

    Path writeString(Path path, String content) throws IOException;
}

