package riid.runtime.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;

/**
 * Builds the OCI layouts a prefix import hands to a runtime that has no
 * per-layer import command: each prefix is a complete little image made of the
 * layers that arrived so far, and the engine reuses what it already unpacked.
 *
 * <p>
 * The layout RIID downloads into is deleted as soon as the last layer lands -
 * before the import is finished - so every blob is hard linked into a directory
 * this object owns as it arrives, and the image config is read while it is
 * still there.
 */
final class PrefixImportLayouts implements AutoCloseable {

    /** Which layer blobs a prefix layout must physically contain. */
    enum LayerScope {
        /**
         * Every layer of the prefix - for an engine that sees only what it is handed.
         */
        ALL,
        /**
         * Only the layers added since the last prefix - for an engine with a content
         * store.
         */
        ADDED_ONLY
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(PrefixImportLayouts.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SHA256 = "sha256:";
    private static final String OCI_IMAGE_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    /**
     * Config blobs are kilobytes and pulled alongside the layers; this is a safety
     * net, not a wait.
     */
    private static final long CONFIG_WAIT_TIMEOUT_MS = 60_000;
    private static final long CONFIG_POLL_MS = 10;

    private final String reference;
    private final Manifest manifest;
    private Path blobsDir;
    private Path workRoot;
    private Path store;
    private byte[] configBytes;
    private int delivered;

    PrefixImportLayouts(String reference, Manifest manifest) {
        this.reference = reference;
        this.manifest = manifest;
    }

    /**
     * Takes over a downloaded layer: checks it is the one the manifest expects
     * next, and puts it where the layouts can still reach it once the download
     * directory is gone.
     */
    void takeLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(blobPath, "blobPath");
        if (delivered >= manifest.layers().size()) {
            throw new IOException("More layers offered than the manifest of " + reference + " declares");
        }
        String declaredDigest = manifest.layers().get(delivered).digest();
        if (!Objects.equals(declaredDigest, layer.digest())) {
            throw new IOException("Layer " + delivered + " of " + reference + " arrived out of manifest order:"
                    + " expected " + declaredDigest + ", got " + layer.digest());
        }
        openStore(blobPath);
        link(blobsDir.resolve(digestHex(layer.digest())), store.resolve(digestHex(layer.digest())));
        delivered++;
    }

    int layersTaken() {
        return delivered;
    }

    int layersExpected() {
        return manifest.layers().size();
    }

    /**
     * An image made of the first {@code count} layers. Its config is the real one
     * with {@code rootfs.diff_ids} cut to match: an engine refuses a config whose
     * diff_ids outnumber the layers it was given.
     */
    Path prefixLayout(int count, String imageName, LayerScope scope, int alreadySent) throws IOException {
        Path layout = layoutDir("p" + count);
        linkLayers(layout, scope, alreadySent, count);
        byte[] prefixConfig = configCutTo(count);
        String configDigest = writeBlob(layout, prefixConfig);
        Manifest prefixManifest = new Manifest(manifest.schemaVersion(), manifest.mediaType(),
                new Descriptor(manifest.config().mediaType(), SHA256 + configDigest, prefixConfig.length),
                manifest.layers().subList(0, count));
        writeLayout(layout, OBJECT_MAPPER.writeValueAsBytes(prefixManifest), imageName);
        return layout;
    }

    /**
     * The image the caller asked for, built from the untouched manifest and config,
     * so it is byte for byte the one an ordinary import would have produced.
     */
    Path fullLayout(String imageName, LayerScope scope, int alreadySent) throws IOException {
        Path layout = layoutDir("full");
        linkLayers(layout, scope, alreadySent, manifest.layers().size());
        writeBlob(layout, configBytes);
        writeLayout(layout, OBJECT_MAPPER.writeValueAsBytes(manifest), imageName);
        return layout;
    }

    @Override
    public void close() throws IOException {
        if (workRoot == null || !Files.exists(workRoot)) {
            return;
        }
        try (var paths = Files.walk(workRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void linkLayers(Path layout, LayerScope scope, int alreadySent, int count) throws IOException {
        for (int i = scope == LayerScope.ALL ? 0 : alreadySent; i < count; i++) {
            link(store.resolve(digestHex(manifest.layers().get(i).digest())),
                    blobsOf(layout).resolve(digestHex(manifest.layers().get(i).digest())));
        }
    }

    private byte[] configCutTo(int count) throws IOException {
        JsonNode config = OBJECT_MAPPER.readTree(configBytes);
        if (!(config instanceof ObjectNode configObject)) {
            throw new IOException("Image config of " + reference + " is not a JSON object");
        }
        if (!(configObject.get("rootfs") instanceof ObjectNode rootfs)
                || !(rootfs.get("diff_ids") instanceof ArrayNode diffIds)) {
            throw new IOException("Image config of " + reference + " has no rootfs.diff_ids");
        }
        if (diffIds.size() < count) {
            throw new IOException("Image config of " + reference + " declares " + diffIds.size()
                    + " diff_ids, fewer than the " + count + " layers imported");
        }
        ArrayNode kept = OBJECT_MAPPER.createArrayNode();
        for (int i = 0; i < count; i++) {
            kept.add(diffIds.get(i));
        }
        rootfs.set("diff_ids", kept);
        // history describes the whole image and is not worth truncating consistently;
        // the intermediate images are thrown away once the real one is in.
        configObject.remove("history");
        return OBJECT_MAPPER.writeValueAsBytes(configObject);
    }

    private void openStore(Path blobPath) throws IOException, InterruptedException {
        if (blobsDir != null) {
            return;
        }
        Path parent = blobPath.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Layer blob has no directory: " + blobPath);
        }
        blobsDir = parent;
        workRoot = Files.createTempDirectory(outsideLayout(parent), "riid-prefix-");
        store = Files.createDirectories(workRoot.resolve("blobs"));
        configBytes = awaitConfigBlob();
    }

    /**
     * Next to the layout, never inside it: the layout directory goes away with the
     * last download and would take these blobs with it.
     */
    private static Path outsideLayout(Path layoutBlobs) {
        Path path = layoutBlobs;
        for (int up = 0; up < 3 && path.getParent() != null; up++) {
            path = path.getParent();
        }
        return path;
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

    private Path layoutDir(String name) throws IOException {
        Path layout = workRoot.resolve(name);
        Files.createDirectories(blobsOf(layout));
        return layout;
    }

    /**
     * Hard link, so holding a blob and laying it out cost no bytes and no copying.
     */
    private static void link(Path source, Path target) throws IOException {
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

    private static String writeBlob(Path layout, byte[] content) throws IOException {
        String hex = sha256Hex(content);
        Files.write(blobsOf(layout).resolve(hex), content);
        return hex;
    }

    private void writeLayout(Path layout, byte[] manifestBytes, String imageName) throws IOException {
        String manifestDigest = writeBlob(layout, manifestBytes);
        Files.writeString(layout.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");
        String declared = manifest.mediaType();
        String mediaType = declared == null || declared.isBlank() ? OCI_IMAGE_MANIFEST : declared;
        Files.writeString(layout.resolve("index.json"), String.format(Locale.ROOT,
                "{\"schemaVersion\":2,\"manifests\":[{\"mediaType\":\"%s\",\"size\":%d,\"digest\":\"sha256:%s\","
                        + "\"annotations\":{\"org.opencontainers.image.ref.name\":\"%s\"}}]}",
                mediaType, manifestBytes.length, manifestDigest, imageName));
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

    /**
     * A reference without a registry host is local to an engine that loads an
     * archive; spelling that out keeps prefix import naming an image exactly as the
     * ordinary path names it.
     */
    static String localReference(String reference) {
        int slash = reference.indexOf('/');
        if (slash > 0) {
            String host = reference.substring(0, slash);
            if (host.indexOf('.') >= 0 || host.indexOf(':') >= 0 || "localhost".equals(host)) {
                return reference;
            }
        }
        return "localhost/" + reference;
    }
}
