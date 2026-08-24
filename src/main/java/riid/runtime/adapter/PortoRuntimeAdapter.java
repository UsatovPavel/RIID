package riid.runtime.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.runtime.BoundedCommandExecution;

/**
 * Porto adapter: imports via {@code portoctl layer}. Accepts an OCI archive
 * (converts to rootfs tar) or a prepared rootfs tar. Requires {@code portoctl}
 * and portod socket (e.g. {@code /run/portod.socket}) accessible.
 */
public class PortoRuntimeAdapter implements RuntimeAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PortoRuntimeAdapter.class);
    private static final String PORTOCTL_BIN = "portoctl";
    private static final String TAR_BIN = "tar";
    private static final String EXIT_SUFFIX = "): ";
    private static final String WHITEOUT_PREFIX = ".wh.";
    private static final String WHITEOUT_OPAQUE = ".wh..wh..opq";
    private static final String OCI_LAYOUT = "oci-layout";
    private static final String INDEX_JSON = "index.json";

    /** Porto keys layers by name alone, so everything about them lives here. */
    private static final class Layer {
        /** {@code portoctl} subcommand behind every layer operation. */
        private static final String CMD = "layer";
        /** Name is derived from the digest: that is what makes a layer reusable. */
        private static final String NAME_PREFIX = "riid-layer-";
        /** Reported when the name is taken - someone imported this digest first. */
        private static final String ALREADY_EXISTS = "LayerAlreadyExists";
        /**
         * Porto caps a private value at 4096 bytes but does not enforce it on import:
         * an oversized value is stored silently, after which the layer can no longer
         * be read or removed via portoctl. Stay under the cap with margin.
         */
        private static final int PRIVATE_VALUE_SAFE_LIMIT = 4000;

        private Layer() {
        }
    }

    @Override
    public RuntimeId runtimeId() {
        return RuntimeId.PORTO;
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

        if (!isOciArchive(imagePath, isGzip(imagePath))) {
            importRootfsTar(imagePath, layerName);
            return;
        }

        Path ociDir = Files.createTempDirectory("porto-import-oci");
        try {
            untar(imagePath, ociDir, isGzip(imagePath));
            Manifest manifest = readManifest(ociDir);

            if (estimateChainJsonBytes(manifest) > Layer.PRIVATE_VALUE_SAFE_LIMIT) {
                LOGGER.info("Layer chain too large for a Porto private value, flattening import for {}", layerName);
                Path rootfsTar = exportRootfsTar(imagePath, null);
                try {
                    importRootfsTar(rootfsTar, layerName);
                } finally {
                    Files.deleteIfExists(rootfsTar);
                }
                return;
            }

            importLayersSeparately(ociDir, manifest, layerName);
        } finally {
            deleteRecursively(ociDir);
        }
    }

    /**
     * Imports every layer under its own digest-derived name, so one already present
     * is reused rather than re-extracted, and records the resulting chain (top layer
     * first) as the marker layer's private value for a later {@code vcreate}.
     */
    private void importLayersSeparately(Path ociDir, Manifest manifest, String layerName)
            throws IOException, InterruptedException {
        Set<String> existing = listExistingPortoLayers();
        List<String> manifestOrderNames = new ArrayList<>();

        Path workDir = Files.createTempDirectory("porto-import-layers");
        try {
            for (Descriptor layer : manifest.layers()) {
                String digest = stripSha256(layer.digest());
                if (digest.isBlank()) {
                    throw new IOException("Layer digest missing in OCI manifest");
                }
                String portoLayerName = Layer.NAME_PREFIX + digest;
                manifestOrderNames.add(portoLayerName);
                if (existing.contains(portoLayerName)) {
                    LOGGER.info("Porto layer already present, skipping import: {}", portoLayerName);
                    continue;
                }

                // Sent compressed: portod detects gzip/zstd/xz/bzip2 by magic bytes, so
                // decompressing here would only cost a round trip and drop the formats
                // we failed to recognise.
                Path layerBlob = blobPath(ociDir, digest);
                LOGGER.info("portoctl layer import (per-layer): {} <- {}", portoLayerName, layerBlob);
                List<String> cmd = List.of(PORTOCTL_BIN, Layer.CMD, "-I", portoLayerName,
                        layerBlob.toAbsolutePath().toString());
                BoundedCommandExecution.ShellResult result = runCommand(cmd);
                if (result.exitCode() != 0 && !isLayerAlreadyExists(result)) {
                    throw new IOException("portoctl layer import failed for " + portoLayerName + " (exit "
                            + result.exitCode() + EXIT_SUFFIX + result.stdout() + result.stderr());
                }
                if (result.exitCode() != 0) {
                    LOGGER.info("Porto layer import raced with a concurrent import, reusing: {}", portoLayerName);
                }
            }

            List<String> topFirstChain = new ArrayList<>(manifestOrderNames);
            Collections.reverse(topFirstChain);
            String chainJson = new ObjectMapper().writeValueAsString(topFirstChain);

            Path emptyDir = Files.createTempDirectory(workDir, "empty-");
            Path emptyTar = workDir.resolve("marker.tar");
            tar(emptyDir, emptyTar);
            List<String> metaCmd = List.of(PORTOCTL_BIN, Layer.CMD, "-S", chainJson, "-I", layerName,
                    emptyTar.toString());
            BoundedCommandExecution.ShellResult metaResult = runCommand(metaCmd);
            if (metaResult.exitCode() != 0 && !isLayerAlreadyExists(metaResult)) {
                throw new IOException("portoctl layer marker import failed (exit " + metaResult.exitCode()
                        + EXIT_SUFFIX + metaResult.stdout() + metaResult.stderr());
            }
            if (metaResult.exitCode() != 0) {
                LOGGER.info("Porto marker layer import raced with a concurrent import of the same image: {}",
                        layerName);
            }
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Names Porto already holds. Only an optimization - anything missed here is still
     * caught by the already-exists error at import time, so a failed listing degrades
     * to "nothing is cached" instead of failing the import.
     */
    private Set<String> listExistingPortoLayers() throws IOException, InterruptedException {
        List<String> cmd = List.of(PORTOCTL_BIN, Layer.CMD, "-L");
        BoundedCommandExecution.ShellResult result = runCommand(cmd);
        if (result.exitCode() != 0) {
            LOGGER.warn("portoctl layer -L failed (exit {}): {}{} - importing every layer without reuse",
                    result.exitCode(), result.stdout(), result.stderr());
            return Set.of();
        }
        return result.stdout().lines().map(String::trim).filter(line -> !line.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Exact UTF-8 size of the JSON chain destined for the marker layer's private
     * value, checked before any import so an oversized chain takes the flattened
     * path instead of wedging a layer.
     */
    private static int estimateChainJsonBytes(Manifest manifest) {
        int count = manifest.layers().size();
        if (count == 0) {
            return "[]".length();
        }
        int bytes = "[]".length() + (count - 1); // brackets + separating commas
        for (Descriptor layer : manifest.layers()) {
            bytes += 2 + Layer.NAME_PREFIX.length() + stripSha256(layer.digest()).length(); // quotes + name
        }
        return bytes;
    }

    /**
     * True when the import failed only because the name is already taken - a
     * concurrent importer won the race on the same content-addressed name.
     */
    private static boolean isLayerAlreadyExists(BoundedCommandExecution.ShellResult result) {
        return result.stdout().contains(Layer.ALREADY_EXISTS)
                || result.stderr().contains(Layer.ALREADY_EXISTS);
    }

    /** Path of a blob inside an extracted OCI layout directory. */
    private static Path blobPath(Path ociDir, String digest) {
        return ociDir.resolve("blobs").resolve("sha256").resolve(digest);
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
     * Export OCI archive into a rootfs tar. If {@code outputTar} is null, a temp
     * file is created.
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
                Path layerBlob = blobPath(ociDir, digest);
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
        long bytes = Files.size(tar);
        LOGGER.info("portoctl layer import starting: layer={} tar={} (~{} MiB)", layerName, tar.toAbsolutePath(),
                bytes / (1024 * 1024));
        List<String> cmd = List.of(PORTOCTL_BIN, Layer.CMD, "-I", layerName, tar.toAbsolutePath().toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        LOGGER.info("portoctl layer import finished: layer={} exit={}", layerName, shellResult.exitCode());
        if (shellResult.exitCode() != 0) {
            throw new IOException("portoctl layer import failed (exit " + shellResult.exitCode() + EXIT_SUFFIX
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
        Path manifestPath = blobPath(ociDir, manifestDigest);
        return mapper.readValue(manifestPath.toFile(), Manifest.class);
    }

    private static void applyLayer(Path layerBlob, boolean gzipLayer, Path rootfsDir)
            throws IOException, InterruptedException {
        List<String> entries = listTarEntries(layerBlob, gzipLayer);
        List<String> whiteouts = applyWhiteoutsFromEntries(entries, rootfsDir);
        untar(layerBlob, rootfsDir, gzipLayer);
        deleteWhiteoutMarkers(whiteouts, rootfsDir);
    }

    private static List<String> applyWhiteoutsFromEntries(List<String> entries, Path rootfsDir) throws IOException {
        List<String> whiteouts = new java.util.ArrayList<>();
        for (String rawEntry : entries) {
            String entry = normalizeTarEntry(rawEntry);
            if (entry.isBlank()) {
                continue;
            }
            Path rel = Path.of(entry);
            Path entryFileName = rel.getFileName();
            if (entryFileName == null) {
                continue;
            }
            String name = entryFileName.toString();
            if (!name.startsWith(WHITEOUT_PREFIX)) {
                continue;
            }
            whiteouts.add(entry);
            Path parentRel = rel.getParent();
            Path parent = parentRel == null ? rootfsDir : rootfsDir.resolve(parentRel);
            if (WHITEOUT_OPAQUE.equals(name)) {
                deleteChildren(parent);
            } else {
                String targetName = name.substring(WHITEOUT_PREFIX.length());
                Path target = parent.resolve(targetName);
                if (!Files.exists(target)) {
                    LOGGER.warn("Whiteout target missing: {}", target);
                    continue;
                }
                deleteRecursively(target);
            }
        }
        return whiteouts;
    }

    private static void deleteWhiteoutMarkers(List<String> whiteouts, Path rootfsDir) throws IOException {
        for (String entry : whiteouts) {
            Path marker = rootfsDir.resolve(entry);
            if (Files.exists(marker)) {
                Files.deleteIfExists(marker);
            }
        }
    }

    private static void deleteChildren(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                deleteRecursively(path);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
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
            throw new IOException(
                    "tar extract failed (exit " + result.exitCode() + EXIT_SUFFIX + result.stdout() + result.stderr());
        }
    }

    private static void tar(Path sourceDir, Path destTar) throws IOException, InterruptedException {
        List<String> cmd = List.of(TAR_BIN, "-cf", destTar.toString(), "-C", sourceDir.toString(), ".");
        BoundedCommandExecution.ShellResult result = BoundedCommandExecution.run(cmd);
        if (result.exitCode() != 0) {
            throw new IOException(
                    "tar create failed (exit " + result.exitCode() + EXIT_SUFFIX + result.stdout() + result.stderr());
        }
    }

    private static List<String> listTarEntries(Path archive, boolean gzip) throws IOException, InterruptedException {
        List<String> cmd = gzip
                ? List.of(TAR_BIN, "-tzf", archive.toString())
                : List.of(TAR_BIN, "-tf", archive.toString());
        BoundedCommandExecution.ShellResult result = BoundedCommandExecution.run(cmd);
        if (result.exitCode() != 0) {
            throw new IOException(
                    "tar list failed (exit " + result.exitCode() + EXIT_SUFFIX + result.stdout() + result.stderr());
        }
        return result.stdout().lines().map(String::trim).filter(line -> !line.isBlank()).toList();
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
