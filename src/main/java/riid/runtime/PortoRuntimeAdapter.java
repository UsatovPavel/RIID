package riid.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
    private static final String PORTOCTL_BIN = "portoctl";
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
            System.out.println("PortoAdapter: extracting OCI " + ociArchive + " gzip=" + gzipArchive);
            untar(ociArchive, ociDir, gzipArchive);
            Manifest manifest = readManifest(ociDir);
            System.out.println("PortoAdapter: layers=" + manifest.layers().size());
            for (Descriptor layer : manifest.layers()) {
                String digest = stripSha256(layer.digest());
                if (digest.isBlank()) {
                    throw new IOException("Layer digest missing in OCI manifest");
                }
                Path layerBlob = ociDir.resolve("blobs").resolve("sha256").resolve(digest);
                boolean gzipLayer = isGzipLayer(layer.mediaType(), layerBlob);
                System.out.println("PortoAdapter: apply layer " + digest + " gzip=" + gzipLayer);
                applyLayer(layerBlob, gzipLayer, rootfsDir);
            }

            Path target = outputTar != null ? outputTar : Files.createTempFile("porto-rootfs-", ".tar");
            tar(rootfsDir, target);
            System.out.println("PortoAdapter: rootfs tar created " + target);
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
            List<Path> whiteouts = findWhiteouts(layerDir);
            for (Path whiteout : whiteouts) {
                Path rel = layerDir.relativize(whiteout);
                Path parentRel = rel.getParent();
                Path parent = parentRel == null ? rootfsDir : rootfsDir.resolve(parentRel);
                String name = whiteout.getFileName().toString();
                if (WHITEOUT_OPAQUE.equals(name)) {
                    deleteChildren(parent);
                } else {
                    String targetName = name.substring(WHITEOUT_PREFIX.length());
                    deleteRecursively(parent.resolve(targetName));
                }
            }
            copyLayer(layerDir, rootfsDir);
        } finally {
            deleteRecursively(layerDir);
        }
    }

    private static List<Path> findWhiteouts(Path layerDir) throws IOException {
        List<Path> whiteouts = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(layerDir)) {
            stream.filter(path -> path.getFileName().toString().startsWith(WHITEOUT_PREFIX))
                    .forEach(whiteouts::add);
        }
        return whiteouts;
    }

    private static void copyLayer(Path layerDir, Path rootfsDir) throws IOException {
        try (Stream<Path> stream = Files.walk(layerDir)) {
            for (Path path : stream.toList()) {
                Path rel = layerDir.relativize(path);
                if (rel.toString().isEmpty()) {
                    continue;
                }
                if (isWhiteout(path)) {
                    continue;
                }
                Path dest = rootfsDir.resolve(rel);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest);
                } else if (Files.isSymbolicLink(path)) {
                    Files.createDirectories(dest.getParent());
                    Path target = Files.readSymbolicLink(path);
                    Files.deleteIfExists(dest);
                    Files.createSymbolicLink(dest, target);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest,
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static boolean isWhiteout(Path path) {
        return path.getFileName().toString().startsWith(WHITEOUT_PREFIX);
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
                ? List.of("tar", "-xzf", archive.toString(), "-C", destDir.toString())
                : List.of("tar", "-xf", archive.toString(), "-C", destDir.toString());
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar extract failed: " + out);
        }
    }

    private static void tar(Path sourceDir, Path destTar) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-cf", destTar.toString(), "-C", sourceDir.toString(), ".")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar create failed: " + out);
        }
    }

    private static List<String> listTarEntries(Path archive, boolean gzip) throws IOException, InterruptedException {
        List<String> cmd = gzip
                ? List.of("tar", "-tzf", archive.toString())
                : List.of("tar", "-tf", archive.toString());
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar list failed: " + out);
        }
        return out.lines()
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
        if (mediaType != null && mediaType.toLowerCase().contains("gzip")) {
            return true;
        }
        return isGzip(layerBlob);
    }

    private static String stripSha256(String digest) {
        return digest == null ? "" : (digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest);
    }
}


