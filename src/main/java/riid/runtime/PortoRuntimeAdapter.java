package riid.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;

/**
 * Porto adapter using portoctl CLI to import images.
 * Accepts OCI archive (converts to rootfs tar) or a prepared rootfs tar.
 * Requires portoctl available and portod socket (/run/portod.socket) accessible.
 */
public class PortoRuntimeAdapter implements RuntimeAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortoRuntimeAdapter.class);
    private static final String PORTOCTL_BIN = "portoctl";
    private static final String TAR_BIN = "tar";
    private static final String WHITEOUT_PREFIX = ".wh.";
    private static final String WHITEOUT_OPAQUE = ".wh..wh..opq";
    private static final String OCI_LAYOUT = "oci-layout";
    private static final String INDEX_JSON = "index.json";

    @Override
    public String runtimeId() {
        return "porto";
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }
        var fileName = imagePath.getFileName();
        if (fileName == null) {
            throw new IOException("Image file has no filename: " + imagePath);
        }
        String layerName = fileName.toString();

        if (isOciArchive(imagePath, isGzip(imagePath))) {
            Path rootfsTar = exportRootfsTar(imagePath, null);
            importRootfsTar(rootfsTar, layerName);
        } else {
            importRootfsTar(imagePath, layerName);
        }
    }

    /**
     * Hook for tests to override process creation.
     */
    protected Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }

    protected BoundedCommandExecution.ShellResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        return BoundedCommandExecution.run(command);
    }

    /**
     * Export OCI archive into a rootfs tar. If outputTar is null, a temp file is created.
     */
    public Path exportRootfsTar(Path ociArchive, Path outputTar) throws IOException, InterruptedException {
        Objects.requireNonNull(ociArchive, "ociArchive");
        if (!ociArchive.toFile().exists()) {
            throw new IOException("Image file not found: " + ociArchive);
        }
        boolean gzipArchive = isGzip(ociArchive);
        if (!isOciArchive(ociArchive, gzipArchive)) {
            throw new IOException("Not an OCI archive: " + ociArchive);
        }

        Path ociDir = Files.createTempDirectory("porto-import-oci");
        Path rootfsDir = Files.createTempDirectory("porto-rootfs");
        try {
            LOGGER.info("Extracting OCI archive {} (gzip={})", ociArchive, gzipArchive);
            untar(ociArchive, ociDir, gzipArchive);
            Manifest manifest = readManifest(ociDir);
            LOGGER.info("OCI layers count={}", manifest.layers().size());
            for (Descriptor layer : manifest.layers()) {
                String digest = stripSha256(layer.digest());
                if (digest.isBlank()) {
                    throw new IOException("Layer digest missing in OCI manifest");
                }
                Path layerBlob = ociDir.resolve("blobs").resolve("sha256").resolve(digest);
                boolean gzipLayer = isGzipLayer(layer.mediaType(), layerBlob);
                LOGGER.info("Applying layer {} (gzip={})", digest, gzipLayer);
                applyLayer(layerBlob, gzipLayer, rootfsDir);
            }

            Path target = outputTar != null ? outputTar : Files.createTempFile("porto-rootfs-", ".tar");
            tar(rootfsDir, target);
            LOGGER.info("Rootfs tar created at {}", target);
            return target;
        } finally {
            deleteRecursively(rootfsDir);
            deleteRecursively(ociDir);
        }
    }

    private void importRootfsTar(Path tar, String layerName) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                PORTOCTL_BIN,
                "layer",
                "-I",
                layerName,
                tar.toAbsolutePath().toString()
        );
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("portoctl layer import failed (exit " + shellResult.exitCode() + "): "
                    + shellResult.stdout() + shellResult.stderr());
        }
    }

    private static boolean isOciArchive(Path archive, boolean gzip) throws IOException, InterruptedException {
        List<String> entries = listTarEntries(archive, gzip);
        boolean hasIndex = entries.stream().anyMatch(entry -> normalizeTarEntry(entry).equals(INDEX_JSON));
        boolean hasLayout = entries.stream().anyMatch(entry -> normalizeTarEntry(entry).equals(OCI_LAYOUT));
        return hasIndex && hasLayout;
    }

    private static Manifest readManifest(Path ociDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode index = mapper.readTree(ociDir.resolve(INDEX_JSON).toFile());
        JsonNode manifestNode = index.path("manifests").get(0);
        if (manifestNode == null || manifestNode.isMissingNode()) {
            throw new IOException("OCI archive missing manifests");
        }
        String manifestDigest = stripSha256(manifestNode.path("digest").asText(""));
        if (manifestDigest.isBlank()) {
            throw new IOException("OCI archive manifest digest missing");
        }
        Path manifestPath = ociDir.resolve("blobs").resolve("sha256").resolve(manifestDigest);
        return mapper.readValue(manifestPath.toFile(), Manifest.class);
    }

    private static void applyLayer(Path layerBlob, boolean gzipLayer, Path rootfsDir)
            throws IOException, InterruptedException {
        Path layerDir = Files.createTempDirectory("porto-layer-");
        try {
            untar(layerBlob, layerDir, gzipLayer);
            applyLayerDir(layerDir, rootfsDir);
        } finally {
            deleteRecursively(layerDir);
        }
    }

    /**
     * Apply extracted layer directory onto rootfs, honoring whiteouts.
     */
    private static void applyLayerDir(Path layerDir, Path rootfsDir) throws IOException {
        // First pass: apply whiteouts
        try (Stream<Path> stream = Files.walk(layerDir)) {
            stream.forEach(path -> {
                var fileName = path.getFileName();
                if (fileName == null) {
                    return;
                }
                String name = fileName.toString();
                if (!name.startsWith(WHITEOUT_PREFIX)) {
                    return;
                }
                Path rel = layerDir.relativize(path);
                Path parentRel = rel.getParent();
                Path parent = parentRel == null ? rootfsDir : rootfsDir.resolve(parentRel);
                if (WHITEOUT_OPAQUE.equals(name)) {
                    try {
                        deleteChildren(parent);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    String targetName = name.substring(WHITEOUT_PREFIX.length());
                    Path target = parent.resolve(targetName);
                    if (!Files.exists(target)) {
                        LOGGER.warn("Whiteout target missing: {}", target);
                        return;
                    }
                    try {
                        deleteRecursively(target);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }

        // Second pass: copy layer contents into rootfs, skipping whiteouts
        try (Stream<Path> stream = Files.walk(layerDir)) {
            for (Path path : stream.toList()) {
                Path rel = layerDir.relativize(path);
                if (rel.toString().isEmpty()) {
                    continue;
                }
                if (isWhiteoutPath(rel)) {
                    continue;
                }
                Path dest = rootfsDir.resolve(rel);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest);
                } else if (Files.isSymbolicLink(path)) {
                    Path parent = dest.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Path target = Files.readSymbolicLink(path);
                    Files.deleteIfExists(dest);
                    Files.createSymbolicLink(dest, target);
                } else {
                    Path parent = dest.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, dest,
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static boolean isWhiteoutPath(Path relPath) {
        Path fileName = relPath.getFileName();
        return fileName != null && fileName.toString().startsWith(WHITEOUT_PREFIX);
    }

    private static void deleteChildren(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.toList();
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(paths.get(i));
            }
        }
    }

    private static void untar(Path archive, Path destDir, boolean gzip) throws IOException, InterruptedException {
        List<String> cmd = gzip
                ? List.of(TAR_BIN, "-xzf", archive.toString(), "-C", destDir.toString())
                : List.of(TAR_BIN, "-xf", archive.toString(), "-C", destDir.toString());
        BoundedCommandExecution.ShellResult result = BoundedCommandExecution.run(cmd);
        if (result.exitCode() != 0) {
            throw new IOException("tar extract failed (exit " + result.exitCode() + "): "
                    + result.stdout() + result.stderr());
        }
    }

    private static void tar(Path sourceDir, Path destTar) throws IOException, InterruptedException {
        List<String> cmd = List.of(TAR_BIN, "-cf", destTar.toString(), "-C", sourceDir.toString(), ".");
        BoundedCommandExecution.ShellResult result = BoundedCommandExecution.run(cmd);
        if (result.exitCode() != 0) {
            throw new IOException("tar create failed (exit " + result.exitCode() + "): "
                    + result.stdout() + result.stderr());
        }
    }

    private static List<String> listTarEntries(Path archive, boolean gzip) throws IOException, InterruptedException {
        List<String> cmd = gzip
                ? List.of(TAR_BIN, "-tzf", archive.toString())
                : List.of(TAR_BIN, "-tf", archive.toString());
        BoundedCommandExecution.ShellResult result = BoundedCommandExecution.run(cmd);
        if (result.exitCode() != 0) {
            throw new IOException("tar list failed (exit " + result.exitCode() + "): "
                    + result.stdout() + result.stderr());
        }
        return result.stdout().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String normalizeTarEntry(String entry) {
        String normalized = entry;
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static boolean isGzip(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            int b1 = in.read();
            int b2 = in.read();
            return b1 == 0x1f && b2 == 0x8b;
        }
    }

    private static boolean isGzipLayer(String mediaType, Path layerBlob) throws IOException {
        if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("gzip")) {
            return true;
        }
        return isGzip(layerBlob);
    }

    private static String stripSha256(String digest) {
        return digest == null ? "" : (digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest);
    }
}


