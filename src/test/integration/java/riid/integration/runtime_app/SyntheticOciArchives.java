package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Builds a minimal synthetic OCI archive for the Porto per-layer import test: a
 * real multi-layer image without touching a registry. Lives next to its only
 * consumer rather than in shared testFixtures - nothing outside the Porto
 * adapter tests needs it.
 */
final class SyntheticOciArchives {
    private SyntheticOciArchives() {
    }

    /**
     * Two-layer OCI archive (plain, non-gzip blobs): layer 1 adds {@code base.txt},
     * layer 2 adds {@code top.txt}. Neither layer deletes or overwrites a path from
     * the other, so it is safe for adapters that require whiteout-free input for a
     * per-layer import path.
     */
    static Path layoutDir(Path workDir) {
        return workDir.resolve("layout");
    }

    static Path buildTwoLayerArchive(Path workDir) throws IOException, InterruptedException {
        Path root = Files.createDirectory(layoutDir(workDir));
        Path blobsDir = Files.createDirectories(root.resolve("blobs").resolve("sha256"));

        byte[] layer1Tar = tarSingleFile("base.txt", "BASE");
        byte[] layer2Tar = tarSingleFile("top.txt", "TOP");
        String layer1Digest = sha256(layer1Tar);
        String layer2Digest = sha256(layer2Tar);
        Files.write(blobsDir.resolve(layer1Digest), layer1Tar);
        Files.write(blobsDir.resolve(layer2Digest), layer2Tar);

        byte[] configBytes = "{}".getBytes(StandardCharsets.UTF_8);
        String configDigest = sha256(configBytes);
        Files.write(blobsDir.resolve(configDigest), configBytes);

        String manifestJson = "{\"schemaVersion\":2,\"mediaType\":"
                + "\"application/vnd.docker.distribution.manifest.v2+json\",\"config\":{\"mediaType\":"
                + "\"application/vnd.docker.container.image.v1+json\",\"digest\":\"sha256:" + configDigest
                + "\",\"size\":" + configBytes.length + "},\"layers\":["
                + "{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar\",\"digest\":\"sha256:" + layer1Digest
                + "\",\"size\":" + layer1Tar.length + "},"
                + "{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar\",\"digest\":\"sha256:" + layer2Digest
                + "\",\"size\":" + layer2Tar.length + "}]}";
        byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        String manifestDigest = sha256(manifestBytes);
        Files.write(blobsDir.resolve(manifestDigest), manifestBytes);

        String indexJson = "{\"schemaVersion\":2,\"manifests\":[{\"mediaType\":"
                + "\"application/vnd.oci.image.manifest.v1+json\",\"digest\":\"sha256:" + manifestDigest
                + "\",\"size\":" + manifestBytes.length + "}]}";
        Files.writeString(root.resolve("index.json"), indexJson);
        Files.writeString(root.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");

        // The archive filename becomes the Porto marker layer name, so keep it
        // unique per build - a fixed name would collide between concurrent runs.
        Path archive = workDir.resolve("synthetic-oci-" + UUID.randomUUID() + ".tar");
        runTar(List.of("tar", "-cf", archive.toString(), "-C", root.toString(), "."));
        return archive;
    }

    /**
     * Byte-for-byte reproducible tar of a single file. Determinism matters: these
     * bytes are hashed into the layer digest, which becomes the Porto layer name -
     * a digest that changed per run would defeat the reuse path under test and leak
     * a fresh riid-layer-* entry on every execution.
     */
    private static byte[] tarSingleFile(String name, String content) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("synthetic-layer-");
        try {
            Path file = dir.resolve(name);
            Files.writeString(file, content);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            Path tar = Files.createTempFile("synthetic-layer-", ".tar");
            try {
                runTar(List.of("tar", "-cf", tar.toString(), "--format=gnu", "--sort=name", "--mtime=@0", "--owner=0",
                        "--group=0", "--numeric-owner", "-C", dir.toString(), "."));
                return Files.readAllBytes(tar);
            } finally {
                Files.deleteIfExists(tar);
            }
        } finally {
            Files.deleteIfExists(dir.resolve(name));
            Files.deleteIfExists(dir);
        }
    }

    private static void runTar(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed: " + cmd + " -> " + code + " output: " + out);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
