package riid.runtime.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.MediaTypes;
import riid.core.model.manifest.OciLayout;

/**
 * Builds the OCI layouts a prefix import hands to a runtime that has no
 * per-layer import command: each prefix is a complete little image made of the
 * layers that arrived so far, and the engine reuses what it already unpacked.
 */
// The layout RIID downloads into is deleted as soon as the last layer lands -
// before the import is finished - so every blob is hard linked into a directory
// this object owns as it arrives, and the config is read while it is still
// there.
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
    private static final String SHA256 = OciLayout.DIGEST_PREFIX;
    /** {@code <layout>/blobs/sha256} -> layout root. */
    private static final int BLOBS_DIR_DEPTH = 3;
    private static final HostFilesystem FS = new NioHostFilesystem();

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
    void takeLayer(Descriptor layer, Path blobPath) throws IOException {
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
        if (workRoot != null) {
            FS.deleteRecursively(workRoot);
        }
    }

    private void linkLayers(Path layout, LayerScope scope, int alreadySent, int count) throws IOException {
        for (int i = scope == LayerScope.ALL ? 0 : alreadySent; i < count; i++) {
            link(store.resolve(digestHex(manifest.layers().get(i).digest())),
                    blobsOf(layout).resolve(digestHex(manifest.layers().get(i).digest())));
        }
    }

    /**
     * The image config with {@code rootfs.diff_ids} cut to {@code count}: an engine
     * rejects a config that claims more layers than the manifest hands it. Every
     * other field is left as the registry wrote it, so the prefix stays a valid
     * image.
     */
    private byte[] configCutTo(int count) throws IOException {
        ObjectNode config = OBJECT_MAPPER.readValue(configBytes, ObjectNode.class);
        ObjectNode rootfs = (ObjectNode) config.required(OciLayout.ROOTFS);
        ArrayNode diffIds = (ArrayNode) rootfs.required(OciLayout.DIFF_IDS);
        if (diffIds.size() < count) {
            throw new IOException("Image config of " + reference + " declares " + diffIds.size()
                    + " diff_ids, fewer than the " + count + " layers imported");
        }
        ArrayNode kept = OBJECT_MAPPER.createArrayNode();
        for (int i = 0; i < count; i++) {
            kept.add(diffIds.get(i));
        }
        rootfs.set(OciLayout.DIFF_IDS, kept);
        // history describes the whole image; the prefixes are thrown away anyway
        config.remove(OciLayout.HISTORY);
        return OBJECT_MAPPER.writeValueAsBytes(config);
    }

    /** Copies the config out while the layout that holds it still exists. */
    void takeImageConfig(Path configBlob) throws IOException {
        configBytes = Files.readAllBytes(configBlob);
    }

    private void openStore(Path blobPath) throws IOException {
        if (blobsDir != null) {
            return;
        }
        Path parent = blobPath.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Layer blob has no directory: " + blobPath);
        }
        if (configBytes == null) {
            throw new IOException("Image config of " + reference + " was not handed over before its layers");
        }
        blobsDir = parent;
        workRoot = Files.createTempDirectory(outsideLayout(parent), "riid-prefix-");
        store = Files.createDirectories(workRoot.resolve("blobs"));
    }

    /**
     * Next to the layout, never inside it: the layout directory goes away with the
     * last download and would take these blobs with it.
     */
    private static Path outsideLayout(Path layoutBlobs) {
        Path path = layoutBlobs;
        for (int up = 0; up < BLOBS_DIR_DEPTH && path.getParent() != null; up++) {
            path = path.getParent();
        }
        return path;
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
        Files.writeString(layout.resolve(OciLayout.MARKER_FILE), OciLayout.MARKER_CONTENT);
        String declared = manifest.mediaType();
        String mediaType = declared == null || declared.isBlank() ? MediaTypes.OCI_IMAGE_MANIFEST : declared;
        Files.writeString(layout.resolve(OciLayout.INDEX_JSON),
                String.format(Locale.ROOT,
                        "{\"schemaVersion\":2,\"%s\":[{\"%s\":\"%s\",\"%s\":%d,\"%s\":\"%s%s\","
                                + "\"%s\":{\"%s\":\"%s\"}}]}",
                        OciLayout.MANIFESTS, OciLayout.MEDIA_TYPE, mediaType, OciLayout.SIZE, manifestBytes.length,
                        OciLayout.DIGEST, SHA256, manifestDigest, OciLayout.ANNOTATIONS, OciLayout.REF_NAME_ANNOTATION,
                        imageName));
    }

    private static Path blobsOf(Path layout) {
        return layout.resolve(OciLayout.BLOBS_DIR).resolve(OciLayout.SHA256_DIR);
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
}
