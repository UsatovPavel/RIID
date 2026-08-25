package riid.runtime.adapter;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.runtime.BoundedCommandExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Podman adapter (WSL2-friendly): {@code podman load -q -i path} for a file;
 * piped layout import uses {@code podman load -q} (stdin is the default input
 * per {@code podman load --help}).
 *
 * <p>
 * Optionally imports a growing prefix of the image while the tail still
 * downloads - see {@link #supportsIncrementalImport(Manifest)}.
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    public static final String PODMAN_BIN = "podman";
    /** Prefix import disabled: one import of the whole image, as before. */
    public static final int PREFIX_IMPORT_OFF = 0;

    private static final Logger LOGGER = LoggerFactory.getLogger(PodmanRuntimeAdapter.class);
    private static final int MAX_PROC_STDERR = 64 * 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OCI_LAYOUT = "oci-layout";
    private static final String INDEX_JSON = "index.json";
    private static final String LAYOUT_VERSION = "{\"imageLayoutVersion\":\"1.0.0\"}";
    private static final String OCI_IMAGE_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    private static final String SHA256 = "sha256:";
    private static final String PREFIX_REPOSITORY = "localhost/riid-prefix-";
    /**
     * Config blobs are kilobytes and pulled alongside the layers; this is a safety
     * net, not a wait.
     */
    private static final long CONFIG_WAIT_TIMEOUT_MS = 60_000;
    private static final long CONFIG_POLL_MS = 10;

    private final int prefixImportStride;

    public PodmanRuntimeAdapter() {
        this(PREFIX_IMPORT_OFF);
    }

    /**
     * @param prefixImportStride
     *            how many layers to accumulate before handing the prefix to podman;
     *            {@link #PREFIX_IMPORT_OFF} keeps the single import of the whole
     *            image.
     */
    public PodmanRuntimeAdapter(int prefixImportStride) {
        this.prefixImportStride = Math.max(PREFIX_IMPORT_OFF, prefixImportStride);
    }

    @Override
    public RuntimeId runtimeId() {
        return RuntimeId.PODMAN;
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        // false: `-i <path>` skips podman load's stdin-only io.Copy(tempfile, stdin)
        // step (cmd/podman/images/load.go) entirely. ~6% faster handoff, confirmed
        // over 4 independent fresh-Dragonfly-install A/B rounds, see bench_log.md.
        return false;
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = List.of(PODMAN_BIN, "load", "-q", "-i", imagePath.toAbsolutePath().toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("podman load failed (exit " + shellResult.exitCode() + "): " + shellResult.stdout()
                    + shellResult.stderr());
        }
    }

    /**
     * Streams {@code tar -cf - -C layout .} into {@code podman load -q} on stdin
     * (no {@code -i -}; that is a bogus path).
     */
    @Override
    public void importOciLayoutDirectory(Path ociLayoutRoot) throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> tarCmd = List.of("tar", "-cf", "-", "-C", root.toString(), ".");
        List<String> loadCmd = List.of(PODMAN_BIN, "load", "-q");
        BoundedCommandExecution.PipedShellResult result = BoundedCommandExecution.runWithStdoutPipedToStdin(tarCmd,
                loadCmd, MAX_PROC_STDERR, this::startProcess);
        result.throwIfFailed("tar", "podman load");
    }

    /**
     * Podman has no per-layer import command, so a prefix is handed over as a whole
     * small image built from the layers that already arrived. Nothing is
     * re-extracted: {@code containers/storage} keys a layer by chain-id
     * ({@code storage_dest.go:1043}) and reuses one it already holds, so every
     * prefix costs only its new top layers and the final import costs almost
     * nothing.
     *
     * <p>
     * Off unless a stride was configured: the extra imports are pure overhead
     * unless they overlap a download, and how much of them the download hides is
     * what the stride tunes.
     */
    @Override
    public boolean supportsIncrementalImport(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return prefixImportStride > PREFIX_IMPORT_OFF && manifest.layers().size() > prefixImportStride;
    }

    @Override
    public IncrementalImageImport beginIncrementalImport(String imageName, Manifest manifest) throws IOException {
        Objects.requireNonNull(imageName, "imageName");
        Objects.requireNonNull(manifest, "manifest");
        if (!supportsIncrementalImport(manifest)) {
            throw new IOException("Image " + imageName + " is not worth importing by prefix: stride "
                    + prefixImportStride + ", " + manifest.layers().size() + " layers");
        }
        return new PodmanIncrementalImport(imageName, manifest);
    }

    /**
     * Imports {@code {L0}}, then {@code {L0,L1}}, ... as the layers land, and the
     * real image once the last one is in. Intermediate images are named after the
     * session and dropped in {@link #finish()}; the image the caller asked for is
     * built from the untouched manifest and config, so it is byte-for-byte the one
     * a plain import would have produced.
     */
    private final class PodmanIncrementalImport implements IncrementalImageImport {
        private final String reference;
        private final Manifest manifest;
        private final List<Descriptor> arrived = new ArrayList<>();
        private final List<String> prefixImages = new ArrayList<>();
        private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
        private Path blobsDir;
        private Path workRoot;
        private Path store;
        private byte[] configBytes;
        private boolean finished;

        private PodmanIncrementalImport(String reference, Manifest manifest) {
            this.reference = reference;
            this.manifest = manifest;
        }

        @Override
        public void importLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(blobPath, "blobPath");
            int position = arrived.size();
            if (position >= manifest.layers().size()) {
                throw new IOException("More layers offered than the manifest of " + reference + " declares");
            }
            String declaredDigest = manifest.layers().get(position).digest();
            if (!Objects.equals(declaredDigest, layer.digest())) {
                throw new IOException("Layer " + position + " of " + reference + " arrived out of manifest order:"
                        + " expected " + declaredDigest + ", got " + layer.digest());
            }
            arrived.add(layer);
            locateBlobs(blobPath);
            linkIntoStore(digestHex(layer.digest()));
            int count = arrived.size();
            if (count < manifest.layers().size() && count % prefixImportStride == 0) {
                importPrefix(count);
            }
        }

        @Override
        public void finish() throws IOException, InterruptedException {
            if (arrived.size() != manifest.layers().size()) {
                throw new IOException("Prefix import of " + reference + " finished with " + arrived.size() + " of "
                        + manifest.layers().size() + " layers");
            }
            Path layout = layoutDir("full");
            linkLayerBlobs(layout, manifest.layers().size());
            writeBlob(layout, configBytes);
            byte[] manifestBytes = OBJECT_MAPPER.writeValueAsBytes(manifest);
            String image = localReference(reference);
            writeLayout(layout, manifestBytes, manifestMediaType(), image);
            pull(layout, image);
            finished = true;
            dropPrefixImages();
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                LOGGER.warn("Prefix import of {} aborted after {} of {} layers; no image published", reference,
                        arrived.size(), manifest.layers().size());
                dropPrefixImagesQuietly();
            }
            if (workRoot != null) {
                deleteRecursively(workRoot);
            }
        }

        /**
         * Builds and imports the image made of the first {@code count} layers. Its
         * config is the real one with {@code rootfs.diff_ids} cut to match - podman
         * refuses a config whose diff_ids outnumber the layers.
         */
        private void importPrefix(int count) throws IOException, InterruptedException {
            Path layout = layoutDir("p" + count);
            linkLayerBlobs(layout, count);
            byte[] configBytes = prefixConfig(count);
            String configDigest = writeBlob(layout, configBytes);
            Manifest prefixManifest = new Manifest(manifest.schemaVersion(), manifest.mediaType(),
                    new Descriptor(manifest.config().mediaType(), SHA256 + configDigest, configBytes.length),
                    arrived.subList(0, count));
            byte[] manifestBytes = OBJECT_MAPPER.writeValueAsBytes(prefixManifest);
            String image = PREFIX_REPOSITORY + sessionId + ":" + count;
            writeLayout(layout, manifestBytes, manifestMediaType(), image);
            pull(layout, image);
            prefixImages.add(image);
            LOGGER.info("Prefix of {} layers handed to podman as {}", count, image);
        }

        private byte[] prefixConfig(int count) throws IOException {
            JsonNode config = OBJECT_MAPPER.readTree(configBytes);
            if (!(config instanceof ObjectNode configObject)) {
                throw new IOException("Image config of " + reference + " is not a JSON object");
            }
            JsonNode rootfs = configObject.get("rootfs");
            if (!(rootfs instanceof ObjectNode rootfsObject) || !(rootfsObject.get("diff_ids") instanceof ArrayNode)) {
                throw new IOException("Image config of " + reference + " has no rootfs.diff_ids");
            }
            ArrayNode diffIds = (ArrayNode) rootfsObject.get("diff_ids");
            if (diffIds.size() < count) {
                throw new IOException("Image config of " + reference + " declares " + diffIds.size()
                        + " diff_ids, fewer than the " + count + " layers imported");
            }
            ArrayNode kept = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < count; i++) {
                kept.add(diffIds.get(i));
            }
            rootfsObject.set("diff_ids", kept);
            // history entries describe the full image and are not worth truncating
            // consistently; the intermediate images are thrown away in finish().
            configObject.remove("history");
            return OBJECT_MAPPER.writeValueAsBytes(configObject);
        }

        /**
         * The config blob is pulled together with the layers, so by the time a layer
         * lands it is normally already there; this only covers the reverse order.
         */
        private byte[] awaitConfigBlob() throws IOException, InterruptedException {
            Path config = blobsDir.resolve(digestHex(manifest.config().digest()));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONFIG_WAIT_TIMEOUT_MS);
            while (!Files.isRegularFile(config) || Files.size(config) < manifest.config().size()) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("Image config " + manifest.config().digest() + " of " + reference
                            + " did not arrive within " + CONFIG_WAIT_TIMEOUT_MS + " ms");
                }
                TimeUnit.MILLISECONDS.sleep(CONFIG_POLL_MS);
            }
            return Files.readAllBytes(config);
        }

        /**
         * The layout this session is fed from is deleted the moment the last layer is
         * downloaded - before {@link #finish()} runs - so every blob is hard linked
         * into a directory this session owns as it arrives, and the image config is
         * read while it is still there.
         */
        private void locateBlobs(Path blobPath) throws IOException, InterruptedException {
            if (blobsDir != null) {
                return;
            }
            Path parent = blobPath.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("Layer blob has no directory: " + blobPath);
            }
            blobsDir = parent;
            workRoot = Files.createTempDirectory(outsideLayout(parent), "podman-prefix-");
            store = workRoot.resolve("blobs");
            Files.createDirectories(store);
            configBytes = awaitConfigBlob();
        }

        /**
         * Next to the layout, never inside it: the layout directory goes away with the
         * last download, and it would take the session's blobs with it.
         */
        private static Path outsideLayout(Path layoutBlobs) {
            Path path = layoutBlobs;
            for (int up = 0; up < 3 && path.getParent() != null; up++) {
                path = path.getParent();
            }
            return path;
        }

        private void linkIntoStore(String hex) throws IOException {
            link(blobsDir.resolve(hex), store.resolve(hex));
        }

        private Path layoutDir(String name) throws IOException {
            Path layout = workRoot.resolve(name);
            Files.createDirectories(blobsOf(layout));
            return layout;
        }

        private void linkLayerBlobs(Path layout, int count) throws IOException {
            for (int i = 0; i < count; i++) {
                linkBlob(layout, digestHex(manifest.layers().get(i).digest()));
            }
        }

        private void linkBlob(Path layout, String hex) throws IOException {
            link(store.resolve(hex), blobsOf(layout).resolve(hex));
        }

        /**
         * Hard link, so holding a blob and laying it out cost no bytes and no copying.
         */
        private void link(Path source, Path target) throws IOException {
            if (Files.exists(target)) {
                return;
            }
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                LOGGER.debug("Hard link to {} refused ({}), copying instead", source, e.toString());
                Files.copy(source, target);
            }
        }

        private String writeBlob(Path layout, byte[] content) throws IOException {
            String hex = sha256Hex(content);
            Files.write(blobsOf(layout).resolve(hex), content);
            return hex;
        }

        private void writeLayout(Path layout, byte[] manifestBytes, String mediaType, String image) throws IOException {
            String manifestDigest = writeBlob(layout, manifestBytes);
            Files.writeString(layout.resolve(OCI_LAYOUT), LAYOUT_VERSION);
            String index = String.format(Locale.ROOT,
                    "{\"schemaVersion\":2,\"manifests\":[{\"mediaType\":\"%s\",\"size\":%d,\"digest\":\"sha256:%s\","
                            + "\"annotations\":{\"org.opencontainers.image.ref.name\":\"%s\"}}]}",
                    mediaType, manifestBytes.length, manifestDigest, image);
            Files.writeString(layout.resolve(INDEX_JSON), index);
        }

        private String manifestMediaType() {
            String declared = manifest.mediaType();
            return declared == null || declared.isBlank() ? OCI_IMAGE_MANIFEST : declared;
        }

        private void pull(Path layout, String image) throws IOException, InterruptedException {
            List<String> cmd = List.of(PODMAN_BIN, "pull", "-q", "oci:" + layout.toAbsolutePath() + ":" + image);
            BoundedCommandExecution.ShellResult result = runCommand(cmd);
            if (result.exitCode() != 0) {
                throw new IOException("podman pull of " + image + " failed (exit " + result.exitCode() + "): "
                        + result.stdout() + result.stderr());
            }
        }

        private void dropPrefixImages() throws IOException, InterruptedException {
            if (prefixImages.isEmpty()) {
                return;
            }
            List<String> cmd = new ArrayList<>(List.of(PODMAN_BIN, "rmi", "-f"));
            cmd.addAll(prefixImages);
            BoundedCommandExecution.ShellResult result = runCommand(cmd);
            if (result.exitCode() != 0) {
                // The layers survive in the store under the image that was just imported.
                LOGGER.warn("Could not drop intermediate prefix images {} (exit {}): {}", prefixImages,
                        result.exitCode(), result.stderr());
            }
        }

        private void dropPrefixImagesQuietly() {
            try {
                dropPrefixImages();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOGGER.warn("Could not drop intermediate prefix images {}: {}", prefixImages, e.toString());
            }
        }
    }

    /**
     * {@code podman load} qualifies a reference without a registry host with
     * {@code localhost/}; {@code podman pull} would send it to Docker Hub instead.
     * Spelling it out keeps the image named exactly as the non-incremental path
     * names it.
     */
    private static String localReference(String reference) {
        int slash = reference.indexOf('/');
        if (slash > 0) {
            String host = reference.substring(0, slash);
            if (host.indexOf('.') >= 0 || host.indexOf(':') >= 0 || "localhost".equals(host)) {
                return reference;
            }
        }
        return "localhost/" + reference;
    }

    private static Path blobsOf(Path layout) {
        return layout.resolve("blobs").resolve("sha256");
    }

    private static String digestHex(String digest) {
        return digest.startsWith(SHA256) ? digest.substring(SHA256.length()) : digest;
    }

    private static String sha256Hex(byte[] content) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
}
