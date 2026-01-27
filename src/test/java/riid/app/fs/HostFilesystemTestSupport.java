package riid.app.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * HostFilesystem helper for tests that isolates temp files in a dedicated directory.
 */
public final class HostFilesystemTestSupport implements HostFilesystem {
    private final NioHostFilesystem delegate;

    private HostFilesystemTestSupport(Path baseTempDir) {
        this.delegate = new NioHostFilesystem(baseTempDir);
    }

    public static HostFilesystemTestSupport create() throws IOException {
        Path baseDir = Files.createTempDirectory("riid-test-fs");
        return new HostFilesystemTestSupport(baseDir);
    }

    @Override
    public Path createTempDirectory(String prefix) throws IOException {
        return delegate.createTempDirectory(prefix);
    }

    @Override
    public Path createTempFile(String prefix, String suffix) throws IOException {
        return delegate.createTempFile(prefix, suffix);
    }

    @Override
    public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return delegate.createTempFile(dir, prefix, suffix);
    }

    @Override
    public Path createDirectories(Path dir) throws IOException {
        return delegate.createDirectories(dir);
    }

    @Override
    public Path copy(Path source, Path target, CopyOption... options) throws IOException {
        return delegate.copy(source, target, options);
    }

    @Override
    public Path write(Path path, byte[] bytes) throws IOException {
        return delegate.write(path, bytes);
    }

    @Override
    public Path writeString(Path path, String content) throws IOException {
        return delegate.writeString(path, content);
    }

    @Override
    public InputStream newInputStream(Path path) throws IOException {
        return delegate.newInputStream(path);
    }

    @Override
    public OutputStream newOutputStream(Path path) throws IOException {
        return delegate.newOutputStream(path);
    }

    @Override
    public boolean exists(Path path) {
        return delegate.exists(path);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return delegate.isRegularFile(path);
    }

    @Override
    public long size(Path path) throws IOException {
        return delegate.size(path);
    }

    @Override
    public String probeContentType(Path path) throws IOException {
        return delegate.probeContentType(path);
    }

    @Override
    public Stream<Path> walk(Path root) throws IOException {
        return delegate.walk(root);
    }

    @Override
    public Path atomicMove(Path source, Path target) throws IOException {
        return delegate.atomicMove(source, target);
    }
}

