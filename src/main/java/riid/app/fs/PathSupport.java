package riid.app.fs;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Path helpers for generating unique temp-like paths (creation is delegated to HostFilesystem).
 */
public final class PathSupport {
    private PathSupport() { }

    public static Path tempPath(String prefix, String suffix) {
        return tempPath(null, prefix, suffix);
    }

    public static Path tempPath(Path baseDir, String prefix, String suffix) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeSuffix = suffix == null ? "" : suffix;
        String name = safePrefix + UUID.randomUUID() + safeSuffix;
        Path root = baseDir != null ? baseDir : Path.of(System.getProperty("java.io.tmpdir"));
        return root.resolve(name);
    }

    public static Path tempDirPath(String prefix) {
        return tempDirPath(null, prefix);
    }

    public static Path tempDirPath(Path baseDir, String prefix) {
        return tempPath(baseDir, prefix, "");
    }
}

