package riid.app.fs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory HostFilesystem for unit tests.
 */
public class InMemoryHostFilesystem implements HostFilesystem {
    private final Map<Path, byte[]> files = new ConcurrentHashMap<>();
    private final Set<Path> directories = ConcurrentHashMap.newKeySet();

    @Override
    public Path createDirectory(Path dir) {
        directories.add(normalize(dir));
        return dir;
    }

    @Override
    public Path createFile(Path path) throws IOException {
        Path normalized = normalize(path);
        if (files.containsKey(normalized)) {
            throw new FileAlreadyExistsException(normalized.toString());
        }
        Path parent = normalized.getParent();
        if (parent != null) {
            directories.add(parent);
        }
        files.put(normalized, new byte[0]);
        return path;
    }

    @Override
    public Path copy(Path source, Path target, CopyOption... options) throws IOException {
        byte[] data = readBytes(source);
        createFile(target);
        files.put(normalize(target), data);
        return target;
    }

    @Override
    public Path write(Path path, byte[] bytes) throws IOException {
        Path normalized = normalize(path);
        if (!files.containsKey(normalized)) {
            createFile(path);
        }
        files.put(normalized, bytes);
        return path;
    }

    @Override
    public Path writeString(Path path, String content) throws IOException {
        return write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public InputStream newInputStream(Path path) throws IOException {
        return new ByteArrayInputStream(readBytes(path));
    }

    @Override
    public OutputStream newOutputStream(Path path) throws IOException {
        Path normalized = normalize(path);
        if (!files.containsKey(normalized)) {
            createFile(path);
        }
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                files.put(normalized, toByteArray());
            }
        };
    }

    @Override
    public boolean exists(Path path) {
        Path normalized = normalize(path);
        return files.containsKey(normalized) || directories.contains(normalized);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return files.containsKey(normalize(path));
    }

    @Override
    public long size(Path path) throws IOException {
        return readBytes(path).length;
    }

    @Override
    public String probeContentType(Path path) {
        return null;
    }

    @Override
    public Stream<Path> walk(Path root) {
        Path normalized = normalize(root);
        return Stream.concat(
                directories.stream(),
                files.keySet().stream())
                .filter(p -> p.startsWith(normalized));
    }

    @Override
    public Path atomicMove(Path source, Path target) throws IOException {
        Path src = normalize(source);
        Path dst = normalize(target);
        if (!files.containsKey(src)) {
            throw new IOException("Missing source file: " + source);
        }
        files.put(dst, files.remove(src));
        return target;
    }

    @Override
    public void deleteIfExists(Path path) {
        Path normalized = normalize(path);
        files.remove(normalized);
        directories.remove(normalized);
    }

    @Override
    public void deleteRecursively(Path root) {
        Path normalized = normalize(root);
        files.keySet().removeIf(p -> p.startsWith(normalized));
        directories.removeIf(p -> p.startsWith(normalized));
    }

    private byte[] readBytes(Path path) throws IOException {
        Path normalized = normalize(path);
        byte[] data = files.get(normalized);
        if (data == null) {
            throw new IOException("File not found: " + path);
        }
        return data;
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

