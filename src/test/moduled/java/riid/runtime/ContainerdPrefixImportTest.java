package riid.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.runtime.BoundedCommandExecution.ShellResult;
import riid.runtime.adapter.ContainerdRuntimeAdapter;
import riid.runtime.adapter.IncrementalImageImport;

/**
 * Prefix import on the containerd side. Unlike podman, containerd keeps a
 * content store, so a prefix tar carries only the layers added since the last
 * one - the rest is resolved from the store.
 */
@Tag("filesystem")
class ContainerdPrefixImportTest {

    private static final String LAYER_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip";
    private static final String CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json";
    private static final String MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
    private static final String IMAGE_NAME = "docker.io/library/python:latest";
    private static final String SHA256 = "sha256:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    private Path workDir;

    @Test
    void importsEveryGrowingPrefixAndThenTheRealImage() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(3);

        runSession(adapter, manifest, 3);

        assertEquals(List.of(1, 2, 3), adapter.manifestLayerCounts(),
                "the engine is offered 1, then 2, then all 3 layers");
        assertEquals(IMAGE_NAME, adapter.importedNames().get(2),
                "containerd keeps the ref.name annotation, so the image name is left untouched");
    }

    /**
     * The point of the containerd variant: bytes streamed stay linear in image
     * size. Re-tarring the whole prefix each time would be quadratic.
     */
    @Test
    void everyLayerBlobIsStreamedExactlyOnce() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(4);

        runSession(adapter, manifest, 4);

        assertEquals(List.of(1, 1, 1, 1), adapter.blobsPerImport(),
                "each import carries only the layer it adds, never the prefix under it");
        assertEquals(4, adapter.distinctLayerBlobsStreamed(), "and every layer is streamed once overall");
    }

    @Test
    void strideGroupsLayersInsteadOfImportingEachOne() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(2);
        Manifest manifest = manifest(5);

        runSession(adapter, manifest, 5);

        assertEquals(List.of(2, 4, 5), adapter.manifestLayerCounts(), "stride 2 hands over {L0,L1}, {L0..L3}, all");
        assertEquals(List.of(2, 2, 1), adapter.blobsPerImport(), "carrying two, two and the last one");
    }

    @Test
    void everyPrefixCarriesAConfigCutToItsOwnLength() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(3);

        runSession(adapter, manifest, 3);

        assertEquals(List.of(1, 2, 3), adapter.diffIdCounts(),
                "a config whose diff_ids outnumber the layers would be rejected");
    }

    @Test
    void dropsIntermediateImagesOnceTheRealOneIsIn() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(3);

        runSession(adapter, manifest, 3);

        assertEquals(adapter.importedNames().subList(0, 2), adapter.removedImages(),
                "exactly the intermediate images are dropped");
    }

    @Test
    void abortedSessionPublishesNoImage() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.importLayer(manifest.layers().get(0), blobs.resolve(digestHex(0)));
        }

        assertFalse(adapter.importedNames().contains(IMAGE_NAME), "half an image must never be published");
        assertEquals(adapter.importedNames(), adapter.removedImages(), "an aborted session cleans up after itself");
    }

    @Test
    void rejectsLayersOfferedOutOfManifestOrder() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            assertThrows(IOException.class,
                    () -> session.importLayer(manifest.layers().get(1), blobs.resolve(digestHex(1))));
        }
    }

    @Test
    void finishBeforeTheLastLayerFails() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.importLayer(manifest.layers().get(0), blobs.resolve(digestHex(0)));
            assertThrows(IOException.class, session::finish);
        }
    }

    @Test
    void onByDefaultButDeclinedWhenThereIsNoPrefixToSplitOff() {
        Manifest threeLayers = manifest(3);

        assertTrue(new ContainerdRuntimeAdapter().supportsIncrementalImport(threeLayers),
                "stride 1 is the production default");
        assertFalse(new RecordingContainerdAdapter(0).supportsIncrementalImport(threeLayers), "0 turns it off");
        assertFalse(new RecordingContainerdAdapter(3).supportsIncrementalImport(threeLayers),
                "a stride as long as the image leaves nothing to overlap");
    }

    private void runSession(RecordingContainerdAdapter adapter, Manifest manifest, int layers) throws Exception {
        Path blobs = layoutWithBlobs(manifest);
        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            for (int i = 0; i < layers; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(digestHex(i)));
            }
            session.finish();
        }
    }

    /**
     * An OCI layout holding the layer blobs and the image config, as RIID fills it
     * in.
     */
    private Path layoutWithBlobs(Manifest manifest) throws IOException {
        Path blobs = Files.createDirectories(workDir.resolve("oci").resolve("blobs").resolve("sha256"));
        for (int i = 0; i < manifest.layers().size(); i++) {
            Files.writeString(blobs.resolve(digestHex(i)), "layer-" + i);
        }
        Files.write(blobs.resolve(hex(manifest.config().digest())), configJson(manifest.layers().size()));
        return blobs;
    }

    private static Manifest manifest(int layerCount) {
        List<Descriptor> layers = new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            layers.add(new Descriptor(LAYER_MEDIA_TYPE, SHA256 + digestHex(i), 8));
        }
        byte[] config = configJson(layerCount);
        // content-addressed like the real thing: the layout stores the config under
        // the hash of its bytes, so a made-up digest would simply not be found
        return new Manifest(2, MANIFEST_MEDIA_TYPE,
                new Descriptor(CONFIG_MEDIA_TYPE, SHA256 + sha256Hex(config), config.length), layers);
    }

    private static byte[] configJson(int layerCount) {
        try {
            var root = OBJECT_MAPPER.createObjectNode();
            var rootfs = root.putObject("rootfs");
            rootfs.put("type", "layers");
            var diffIds = rootfs.putArray("diff_ids");
            for (int i = 0; i < layerCount; i++) {
                diffIds.add(SHA256 + "d".repeat(63) + i);
            }
            root.putArray("history").addObject().put("created_by", "test");
            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digestHex(int index) {
        return Integer.toHexString(index + 10).repeat(64).substring(0, 64);
    }

    private static String hex(String digest) {
        return digest.substring(SHA256.length());
    }

    /**
     * Reads each layout while it exists instead of running {@code tar} and
     * {@code ctr}.
     */
    private static final class RecordingContainerdAdapter extends ContainerdRuntimeAdapter {
        private final List<String> imported = new CopyOnWriteArrayList<>();
        private final List<Integer> layerCounts = new CopyOnWriteArrayList<>();
        private final List<Integer> diffIds = new CopyOnWriteArrayList<>();
        private final List<Integer> blobs = new CopyOnWriteArrayList<>();
        private final List<String> streamedBlobs = new CopyOnWriteArrayList<>();
        private final List<String> removed = new CopyOnWriteArrayList<>();

        private RecordingContainerdAdapter(int stride) {
            super(CTR_BIN, null, null, null, stride);
        }

        private List<String> importedNames() {
            return imported;
        }

        private List<Integer> manifestLayerCounts() {
            return layerCounts;
        }

        private List<Integer> diffIdCounts() {
            return diffIds;
        }

        /** Layer blobs physically present in each handed-over layout. */
        private List<Integer> blobsPerImport() {
            return blobs;
        }

        private int distinctLayerBlobsStreamed() {
            return Set.copyOf(streamedBlobs).size();
        }

        private List<String> removedImages() {
            return removed;
        }

        @Override
        public void importOciLayoutDirectory(Path layout) throws IOException {
            JsonNode index = OBJECT_MAPPER.readTree(layout.resolve("index.json").toFile());
            JsonNode descriptor = index.get("manifests").get(0);
            imported.add(descriptor.get("annotations").get("org.opencontainers.image.ref.name").asText());
            JsonNode manifest = OBJECT_MAPPER.readTree(blobOf(layout, descriptor.get("digest").asText()));
            layerCounts.add(manifest.get("layers").size());
            diffIds.add(OBJECT_MAPPER.readTree(blobOf(layout, manifest.get("config").get("digest").asText()))
                    .get("rootfs").get("diff_ids").size());
            int present = 0;
            for (JsonNode layer : manifest.get("layers")) {
                Path blob = layout.resolve("blobs").resolve("sha256").resolve(hex(layer.get("digest").asText()));
                if (Files.exists(blob)) {
                    present++;
                    streamedBlobs.add(layer.get("digest").asText());
                }
            }
            blobs.add(present);
        }

        private static byte[] blobOf(Path layout, String digest) throws IOException {
            return Files.readAllBytes(layout.resolve("blobs").resolve("sha256").resolve(hex(digest)));
        }

        @Override
        protected ShellResult runCommand(List<String> command) {
            if (command.contains("rm")) {
                removed.addAll(command.subList(command.indexOf("rm") + 1, command.size()));
            }
            return new ShellResult(0, "", "");
        }
    }
}
