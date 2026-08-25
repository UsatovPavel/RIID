package riid.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import riid.runtime.adapter.IncrementalImageImport;
import riid.runtime.adapter.PodmanRuntimeAdapter;

/**
 * Prefix import on the Podman side: podman has no per-layer command, so a
 * prefix is handed over as a whole small image built from the layers that
 * arrived, and the real image is imported last.
 */
@Tag("filesystem")
class PodmanPrefixImportTest {

    private static final String LAYER_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip";
    private static final String CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json";
    private static final String MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
    private static final String IMAGE_NAME = "library/python:latest";
    private static final String LOCAL_IMAGE_NAME = "localhost/library/python:latest";
    private static final String SHA256 = "sha256:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    private Path workDir;

    @Test
    void importsEveryGrowingPrefixAndThenTheRealImage() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        assertTrue(adapter.supportsIncrementalImport(manifest));
        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            for (int i = 0; i < 3; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(digestHex(i)));
            }
            session.finish();
        }

        List<String> pulled = adapter.pulledImages();
        assertEquals(3, pulled.size(), "two prefixes plus the real image");
        assertTrue(pulled.get(0).endsWith(":1") && pulled.get(1).endsWith(":2"),
                "prefixes grow one layer at a time: " + pulled);
        assertEquals(LOCAL_IMAGE_NAME, pulled.get(2),
                "the real image must be named exactly as a plain podman load names it");
    }

    /**
     * Podman refuses a config whose {@code diff_ids} outnumber the layers, so each
     * prefix carries a config cut to its own length.
     */
    @Test
    void everyPrefixCarriesAConfigCutToItsOwnLength() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            for (int i = 0; i < 3; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(digestHex(i)));
            }
            session.finish();
        }

        assertEquals(List.of(1, 2), adapter.prefixLayerCounts(), "prefix manifests must list 1 then 2 layers");
        assertEquals(List.of(1, 2), adapter.prefixDiffIdCounts(), "and their configs must match layer for layer");
        assertEquals(3, adapter.finalDiffIdCount(), "the published image keeps the untouched config");
    }

    @Test
    void strideGroupsLayersInsteadOfImportingEachOne() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(2);
        Manifest manifest = manifest(5);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            for (int i = 0; i < 5; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(digestHex(i)));
            }
            session.finish();
        }

        assertEquals(List.of(2, 4), adapter.prefixLayerCounts(), "stride 2 hands over {L0,L1} then {L0..L3}");
    }

    @Test
    void dropsIntermediateImagesOnceTheRealOneIsIn() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            for (int i = 0; i < 3; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(digestHex(i)));
            }
            assertTrue(adapter.removedImages().isEmpty(), "nothing may be removed while the image is incomplete");
            session.finish();
        }

        assertEquals(adapter.pulledImages().subList(0, 2), adapter.removedImages(),
                "exactly the intermediate images are dropped, and only after the real one is in");
    }

    @Test
    void abortedSessionPublishesNoImage() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.importLayer(manifest.layers().get(0), blobs.resolve(digestHex(0)));
        }

        assertFalse(adapter.pulledImages().contains(LOCAL_IMAGE_NAME), "half an image must never be published");
        assertEquals(adapter.pulledImages(), adapter.removedImages(), "an aborted session cleans up after itself");
    }

    @Test
    void rejectsLayersOfferedOutOfManifestOrder() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            assertThrows(IOException.class,
                    () -> session.importLayer(manifest.layers().get(1), blobs.resolve(digestHex(1))));
        }
    }

    @Test
    void finishBeforeTheLastLayerFails() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(1);
        Manifest manifest = manifest(3);
        Path blobs = layoutWithBlobs(manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.importLayer(manifest.layers().get(0), blobs.resolve(digestHex(0)));
            assertThrows(IOException.class, session::finish);
        }
    }

    /**
     * The extra imports only pay off if a download hides them, so they stay off
     * until a stride is configured, and an image with nothing to split is left to
     * the ordinary path.
     */
    @Test
    void offByDefaultAndDeclinedWhenThereIsNoPrefixToSplitOff() {
        Manifest threeLayers = manifest(3);

        assertFalse(new PodmanRuntimeAdapter().supportsIncrementalImport(threeLayers), "off unless configured");
        assertFalse(new RecordingPodmanAdapter(3).supportsIncrementalImport(threeLayers),
                "a stride as long as the image leaves nothing to overlap");
        assertTrue(new RecordingPodmanAdapter(2).supportsIncrementalImport(threeLayers));
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

    private static byte[] configJson(int layerCount) throws IOException {
        var root = OBJECT_MAPPER.createObjectNode();
        var rootfs = root.putObject("rootfs");
        rootfs.put("type", "layers");
        var diffIds = rootfs.putArray("diff_ids");
        for (int i = 0; i < layerCount; i++) {
            diffIds.add(SHA256 + "d".repeat(63) + i);
        }
        root.putArray("history").addObject().put("created_by", "test");
        return OBJECT_MAPPER.writeValueAsBytes(root);
    }

    private static Manifest manifest(int layerCount) {
        List<Descriptor> layers = new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            layers.add(new Descriptor(LAYER_MEDIA_TYPE, SHA256 + digestHex(i), 8));
        }
        Descriptor config = new Descriptor(CONFIG_MEDIA_TYPE, SHA256 + "c".repeat(64), 64);
        return new Manifest(2, MANIFEST_MEDIA_TYPE, config, layers);
    }

    private static String digestHex(int index) {
        return Integer.toHexString(index + 10).repeat(64).substring(0, 64);
    }

    private static String hex(String digest) {
        return digest.substring(SHA256.length());
    }

    /**
     * Records {@code podman} invocations and reads back the layouts they were
     * pointed at.
     */
    private static final class RecordingPodmanAdapter extends PodmanRuntimeAdapter {
        private final List<String> pulled = new CopyOnWriteArrayList<>();
        private final List<Integer> layerCounts = new CopyOnWriteArrayList<>();
        private final List<Integer> diffIdCounts = new CopyOnWriteArrayList<>();
        private final List<String> removed = new CopyOnWriteArrayList<>();

        private RecordingPodmanAdapter(int stride) {
            super(stride);
        }

        private List<String> pulledImages() {
            return pulled;
        }

        private List<String> removedImages() {
            return removed;
        }

        /** How many layers each intermediate image was built from, in order. */
        private List<Integer> prefixLayerCounts() {
            return layerCounts.subList(0, layerCounts.size() - 1);
        }

        private List<Integer> prefixDiffIdCounts() {
            return diffIdCounts.subList(0, diffIdCounts.size() - 1);
        }

        private int finalDiffIdCount() {
            return diffIdCounts.get(diffIdCounts.size() - 1);
        }

        /**
         * Reads the layout while it is still there - the session deletes it on close.
         */
        private void recordLayout(Path layout) throws IOException {
            JsonNode index = OBJECT_MAPPER.readTree(layout.resolve("index.json").toFile());
            JsonNode manifest = OBJECT_MAPPER
                    .readTree(blobOf(layout, index.get("manifests").get(0).get("digest").asText()));
            layerCounts.add(manifest.get("layers").size());
            String configDigest = manifest.get("config").get("digest").asText();
            diffIdCounts.add(OBJECT_MAPPER.readTree(blobOf(layout, configDigest)).get("rootfs").get("diff_ids").size());
        }

        private static byte[] blobOf(Path layout, String digest) throws IOException {
            return Files.readAllBytes(layout.resolve("blobs").resolve("sha256").resolve(hex(digest)));
        }

        @Override
        protected ShellResult runCommand(List<String> command) throws IOException {
            if (command.contains("pull")) {
                String reference = command.get(command.size() - 1).substring("oci:".length());
                int split = reference.indexOf(':');
                recordLayout(Path.of(reference.substring(0, split)));
                pulled.add(reference.substring(split + 1));
            } else if (command.contains("rmi")) {
                removed.addAll(command.subList(3, command.size()));
            }
            return new ShellResult(0, "", "");
        }
    }
}
