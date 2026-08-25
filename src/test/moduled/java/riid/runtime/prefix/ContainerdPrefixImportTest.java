package riid.runtime.prefix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static riid.runtime.prefix.PrefixImportFixtures.hex;
import static riid.runtime.prefix.PrefixImportFixtures.layerDigestHex;
import static riid.runtime.prefix.PrefixImportFixtures.layoutWithBlobs;
import static riid.runtime.prefix.PrefixImportFixtures.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static final String IMAGE_NAME = "docker.io/library/python:latest";
    private static final int LAYERS = 3;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    private Path workDir;

    @Test
    void importsEveryGrowingPrefixAndThenTheRealImage() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);

        runSession(adapter, manifest(LAYERS), LAYERS);

        assertEquals(List.of(1, 2, LAYERS), adapter.manifestLayerCounts(), "1, then 2, then all layers");
        assertEquals(IMAGE_NAME, adapter.importedNames().get(LAYERS - 1),
                "containerd keeps the ref.name annotation, so the image name is left untouched");
    }

    /**
     * The point of the containerd variant: bytes streamed stay linear in image
     * size. Re-tarring the whole prefix each time would be quadratic.
     */
    @Test
    void everyLayerBlobIsStreamedExactlyOnce() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);

        runSession(adapter, manifest(LAYERS), LAYERS);

        assertEquals(List.of(1, 1, 1), adapter.blobsPerImport(),
                "each import carries only the layer it adds, never the prefix under it");
        assertEquals(LAYERS, adapter.distinctLayerBlobsStreamed(), "and every layer is streamed once overall");
    }

    @Test
    void everyPrefixCarriesAConfigCutToItsOwnLength() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);

        runSession(adapter, manifest(LAYERS), LAYERS);

        assertEquals(List.of(1, 2, LAYERS), adapter.diffIdCounts(),
                "a config whose diff_ids outnumber the layers would be rejected");
    }

    @Test
    void dropsIntermediateImagesOnceTheRealOneIsIn() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);

        runSession(adapter, manifest(LAYERS), LAYERS);

        assertEquals(adapter.importedNames().subList(0, LAYERS - 1), adapter.removedImages(),
                "exactly the intermediate images are dropped");
    }

    @Test
    void abortedSessionPublishesNoImage() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);
        Manifest manifest = manifest(LAYERS);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            session.importLayer(manifest.layers().get(0), blobs.resolve(layerDigestHex(0)));
        }

        assertFalse(adapter.importedNames().contains(IMAGE_NAME), "half an image must never be published");
        assertEquals(adapter.importedNames(), adapter.removedImages(), "an aborted session cleans up after itself");
    }

    @Test
    void rejectsLayersOfferedOutOfManifestOrder() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);
        Manifest manifest = manifest(LAYERS);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            assertThrows(IOException.class,
                    () -> session.importLayer(manifest.layers().get(1), blobs.resolve(layerDigestHex(1))));
        }
    }

    @Test
    void finishBeforeTheLastLayerFails() throws Exception {
        RecordingContainerdAdapter adapter = new RecordingContainerdAdapter(true);
        Manifest manifest = manifest(LAYERS);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            session.importLayer(manifest.layers().get(0), blobs.resolve(layerDigestHex(0)));
            assertThrows(IOException.class, session::finish);
        }
    }

    @Test
    void onByDefaultButDeclinedWhenThereIsNoPrefixToSplitOff() {
        assertTrue(new ContainerdRuntimeAdapter().supportsIncrementalImport(manifest(LAYERS)), "on by default");
        assertFalse(new RecordingContainerdAdapter(false).supportsIncrementalImport(manifest(LAYERS)), "switched off");
        assertFalse(new RecordingContainerdAdapter(true).supportsIncrementalImport(manifest(1)),
                "a one-layer image has no prefix to overlap with");
    }

    private void runSession(RecordingContainerdAdapter adapter, Manifest manifest, int layers) throws Exception {
        Path blobs = layoutWithBlobs(workDir, manifest);
        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE_NAME, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            for (int i = 0; i < layers; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(layerDigestHex(i)));
            }
            session.finish();
        }
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

        private RecordingContainerdAdapter(boolean prefixImport) {
            super(CTR_BIN, null, null, null, prefixImport);
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
                removed.addAll(new ArrayList<>(command.subList(command.indexOf("rm") + 1, command.size())));
            }
            return new ShellResult(0, "", "");
        }
    }
}
