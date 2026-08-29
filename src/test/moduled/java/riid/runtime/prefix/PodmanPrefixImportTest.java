package riid.runtime.prefix;

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
import riid.core.model.manifest.OciLayout;
import riid.runtime.BoundedCommandExecution.ShellResult;
import riid.runtime.adapter.ImageReference;
import riid.runtime.adapter.IncrementalImageImport;
import riid.runtime.adapter.PodmanRuntimeAdapter;

/**
 * Prefix import on the Podman side: podman has no per-layer command, so a
 * prefix is handed over as a whole small image built from the layers that
 * arrived, and the real image is imported last.
 */
@Tag("filesystem")
class PodmanPrefixImportTest {

    private static final ImageReference IMAGE = new ImageReference("library/python", "latest");
    private static final String LOCAL_IMAGE_NAME = "localhost/library/python:latest";
    private static final int LAYERS_COUNT = 3;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    private Path workDir;

    @Test
    void importsEveryGrowingPrefixAndThenTheRealImage() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);
        Manifest manifest = manifest(LAYERS_COUNT);

        assertTrue(adapter.supportsIncrementalImport(manifest));
        runSession(adapter, manifest, LAYERS_COUNT);

        List<String> pulled = adapter.pulledImages();
        assertEquals(LAYERS_COUNT, pulled.size(), "two prefixes plus the real image");
        assertTrue(pulled.get(0).endsWith(":1") && pulled.get(1).endsWith(":2"),
                "prefixes grow one layer at a time: " + pulled);
        assertEquals(LOCAL_IMAGE_NAME, pulled.get(LAYERS_COUNT - 1),
                "the real image must be named exactly as a plain podman load names it");
    }

    /**
     * Podman refuses a config whose {@code diff_ids} outnumber the layers, so each
     * prefix carries a config cut to its own length.
     */
    @Test
    void everyPrefixCarriesAConfigCutToItsOwnLength() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);

        runSession(adapter, manifest(LAYERS_COUNT), LAYERS_COUNT);

        assertEquals(List.of(1, 2, LAYERS_COUNT), adapter.manifestLayerCounts(), "1, then 2, then the whole image");
        assertEquals(List.of(1, 2, LAYERS_COUNT), adapter.diffIdCounts(), "configs must match layer for layer");
    }

    @Test
    void dropsIntermediateImagesOnceTheRealOneIsIn() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);
        Manifest manifest = manifest(LAYERS_COUNT);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            for (int i = 0; i < LAYERS_COUNT; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(layerDigestHex(i)));
            }
            assertTrue(adapter.removedImages().isEmpty(), "nothing may be removed while the image is incomplete");
            session.finish();
        }

        assertEquals(adapter.pulledImages().subList(0, LAYERS_COUNT - 1), adapter.removedImages(),
                "exactly the intermediate images are dropped, and only after the real one is in");
    }

    @Test
    void abortedSessionPublishesNoImage() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);
        Manifest manifest = manifest(LAYERS_COUNT);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            session.importLayer(manifest.layers().get(0), blobs.resolve(layerDigestHex(0)));
        }

        assertFalse(adapter.pulledImages().contains(LOCAL_IMAGE_NAME), "half an image must never be published");
        assertEquals(adapter.pulledImages(), adapter.removedImages(), "an aborted session cleans up after itself");
    }

    @Test
    void rejectsLayersOfferedOutOfManifestOrder() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);
        Manifest manifest = manifest(LAYERS_COUNT);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            assertThrows(IOException.class,
                    () -> session.importLayer(manifest.layers().get(1), blobs.resolve(layerDigestHex(1))));
        }
    }

    @Test
    void finishBeforeTheLastLayerFails() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);
        Manifest manifest = manifest(LAYERS_COUNT);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            session.importLayer(manifest.layers().get(0), blobs.resolve(layerDigestHex(0)));
            assertThrows(IOException.class, session::finish);
        }
    }

    /** A layer handed over before its config would have nothing to describe it. */
    @Test
    void refusesLayersBeforeTheConfigArrives() throws Exception {
        RecordingPodmanAdapter adapter = new RecordingPodmanAdapter(true);
        Manifest manifest = manifest(LAYERS_COUNT);
        Path blobs = layoutWithBlobs(workDir, manifest);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            assertThrows(IOException.class,
                    () -> session.importLayer(manifest.layers().get(0), blobs.resolve(layerDigestHex(0))));
        }
    }

    /**
     * The extra imports only pay off if a download hides them, so a single-layer
     * image is left to the ordinary path.
     */
    @Test
    void onByDefaultButDeclinedWhenThereIsNoPrefixToSplitOff() {
        assertTrue(new PodmanRuntimeAdapter().supportsIncrementalImport(manifest(LAYERS_COUNT)), "on by default");
        assertFalse(new RecordingPodmanAdapter(false).supportsIncrementalImport(manifest(LAYERS_COUNT)),
                "switched off");
        assertFalse(new RecordingPodmanAdapter(true).supportsIncrementalImport(manifest(1)),
                "a one-layer image has no prefix to overlap with");
    }

    private void runSession(RecordingPodmanAdapter adapter, Manifest manifest, int layers) throws Exception {
        Path blobs = layoutWithBlobs(workDir, manifest);
        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            for (int i = 0; i < layers; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(layerDigestHex(i)));
            }
            session.finish();
        }
    }

    /** Reads each layout while it exists instead of running {@code podman}. */
    private static final class RecordingPodmanAdapter extends PodmanRuntimeAdapter {
        private static final int RMI_ARGUMENTS = 3;

        private final List<String> pulled = new CopyOnWriteArrayList<>();
        private final List<Integer> layerCounts = new CopyOnWriteArrayList<>();
        private final List<Integer> diffIds = new CopyOnWriteArrayList<>();
        private final List<String> removed = new CopyOnWriteArrayList<>();

        private RecordingPodmanAdapter(boolean prefixImport) {
            super(prefixImport, "");
        }

        private List<String> pulledImages() {
            return pulled;
        }

        private List<Integer> manifestLayerCounts() {
            return layerCounts;
        }

        private List<Integer> diffIdCounts() {
            return diffIds;
        }

        private List<String> removedImages() {
            return removed;
        }

        private void recordLayout(Path layout) throws IOException {
            JsonNode index = OBJECT_MAPPER.readTree(layout.resolve(OciLayout.INDEX_JSON).toFile());
            JsonNode descriptor = index.get(OciLayout.MANIFESTS).get(0);
            JsonNode manifest = OBJECT_MAPPER.readTree(blobOf(layout, descriptor.get(OciLayout.DIGEST).asText()));
            layerCounts.add(manifest.get(OciLayout.LAYERS).size());
            String configDigest = manifest.get(OciLayout.CONFIG).get(OciLayout.DIGEST).asText();
            diffIds.add(OBJECT_MAPPER.readTree(blobOf(layout, configDigest)).get(OciLayout.ROOTFS)
                    .get(OciLayout.DIFF_IDS).size());
        }

        private static byte[] blobOf(Path layout, String digest) throws IOException {
            return Files.readAllBytes(
                    layout.resolve(OciLayout.BLOBS_DIR).resolve(OciLayout.SHA256_DIR).resolve(hex(digest)));
        }

        @Override
        protected ShellResult runCommand(List<String> command) throws IOException {
            if (command.contains("pull")) {
                String reference = command.get(command.size() - 1).substring("oci:".length());
                int split = reference.indexOf(':');
                recordLayout(Path.of(reference.substring(0, split)));
                pulled.add(reference.substring(split + 1));
            } else if (command.contains("rmi")) {
                removed.addAll(new ArrayList<>(command.subList(RMI_ARGUMENTS, command.size())));
            }
            return new ShellResult(0, "", "");
        }
    }
}
