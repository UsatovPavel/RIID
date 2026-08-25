package riid.runtime.prefix;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.TestManifests;
import riid.runtime.BoundedCommandExecution.ShellResult;
import riid.runtime.adapter.ImageReference;
import riid.runtime.adapter.IncrementalImageImport;
import riid.runtime.adapter.PortoRuntimeAdapter;

/**
 * Prefix import (AGENT-90) on the Porto side: layers go in one at a time under
 * their content-addressed names, and the image marker is written only after the
 * last one.
 */
@Tag("filesystem")
class PortoPrefixImportTest {

    private static final ImageReference IMAGE = new ImageReference("library/alpine", "edge");
    private static final String SANITIZED_IMAGE_NAME = "library_alpine:edge";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void importsLayersOneByOneAndPublishesTheImageOnlyAtTheEnd() throws Exception {
        RecordingPortoAdapter adapter = new RecordingPortoAdapter();
        Manifest manifest = manifest(2);

        assertTrue(adapter.supportsIncrementalImport(manifest));
        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.importLayer(manifest.layers().get(0), blobPath(0));
            assertTrue(adapter.markerImports().isEmpty(), "image must not exist in Porto before its last layer");
            session.importLayer(manifest.layers().get(1), blobPath(1));
            assertTrue(adapter.markerImports().isEmpty(), "image must not exist in Porto before finish()");
            session.finish();
        }

        assertEquals(List.of(importOf(0), importOf(1)), adapter.layerImports(),
                "each layer must be imported under its own digest-derived name, in manifest order");
        assertEquals(List.of(SANITIZED_IMAGE_NAME), adapter.markerImports(),
                "exactly one image marker, named after" + " the image with characters Porto rejects replaced");
        assertEquals(List.of(layerName(1), layerName(0)), adapter.markerChain(),
                "marker private value must hold the layer chain top-first");
    }

    @Test
    void reusesLayersPortoAlreadyHas() throws Exception {
        Manifest manifest = manifest(2);
        RecordingPortoAdapter adapter = new RecordingPortoAdapter();
        adapter.pretendPortoHas(layerName(0));

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.importLayer(manifest.layers().get(0), blobPath(0));
            session.importLayer(manifest.layers().get(1), blobPath(1));
            session.finish();
        }

        assertEquals(List.of(importOf(1)), adapter.layerImports(),
                "a layer already in Porto must not be imported again");
        assertEquals(List.of(layerName(1), layerName(0)), adapter.markerChain(),
                "the reused layer still belongs to the chain");
    }

    @Test
    void rejectsLayersOfferedOutOfManifestOrder() throws Exception {
        RecordingPortoAdapter adapter = new RecordingPortoAdapter();
        Manifest manifest = manifest(2);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            assertThrows(IOException.class, () -> session.importLayer(manifest.layers().get(1), blobPath(1)));
        }
    }

    @Test
    void abortedSessionPublishesNoImage() throws Exception {
        RecordingPortoAdapter adapter = new RecordingPortoAdapter();
        Manifest manifest = manifest(2);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.importLayer(manifest.layers().get(0), blobPath(0));
        }

        assertTrue(adapter.markerImports().isEmpty(), "half an image must never show up as a finished one");
    }

    @Test
    void finishBeforeTheLastLayerFails() throws Exception {
        RecordingPortoAdapter adapter = new RecordingPortoAdapter();
        Manifest manifest = manifest(2);

        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.importLayer(manifest.layers().get(0), blobPath(0));
            assertThrows(IOException.class, session::finish);
        }
    }

    /**
     * A chain too long for a Porto private value has to go the flattened way, so
     * the incremental path must decline it up front, before anything is downloaded.
     */
    @Test
    void declinesImagesWhoseChainDoesNotFitPortoMetadata() {
        RecordingPortoAdapter adapter = new RecordingPortoAdapter();

        assertFalse(adapter.supportsIncrementalImport(manifest(60)), "60 layers do not fit a 4096-byte private value");
        assertFalse(adapter.supportsIncrementalImport(manifest(0)), "an image without layers has nothing to stream");
        assertTrue(adapter.supportsIncrementalImport(manifest(50)), "50 layers still fit");
    }

    /**
     * Never opened - {@code runCommand} is stubbed - so a relative name is enough.
     */
    private static Path blobPath(int index) {
        return Path.of("blobs", String.valueOf(index));
    }

    private static String importOf(int index) {
        return layerName(index) + " <- " + blobPath(index).toAbsolutePath();
    }

    private static String layerName(int index) {
        return "riid-layer-" + digestHex(index);
    }

    private static String digestHex(int index) {
        return Integer.toHexString(index).repeat(64).substring(0, 64);
    }

    private static Manifest manifest(int layerCount) {
        List<Descriptor> layers = new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            layers.add(TestManifests.gzipLayer(TestManifests.SHA256 + digestHex(i), 3));
        }
        return TestManifests.manifest(TestManifests.config(TestManifests.digest('f'), 3), layers);
    }

    /** Records {@code portoctl} invocations instead of running them. */
    private static final class RecordingPortoAdapter extends PortoRuntimeAdapter {
        private final List<String> recordedLayerImports = new CopyOnWriteArrayList<>();
        private final List<String> recordedMarkerImports = new CopyOnWriteArrayList<>();
        private final List<String> existingLayers = new CopyOnWriteArrayList<>();
        private volatile String markerChainJson;

        private void pretendPortoHas(String layerName) {
            existingLayers.add(layerName);
        }

        private List<String> layerImports() {
            return recordedLayerImports;
        }

        private List<String> markerImports() {
            return recordedMarkerImports;
        }

        private List<String> markerChain() throws IOException {
            return OBJECT_MAPPER.readValue(markerChainJson, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        }

        @Override
        protected ShellResult runCommand(List<String> command) {
            if (command.contains("-L")) {
                return new ShellResult(0, String.join("\n", existingLayers), "");
            }
            if (command.contains("-R")) {
                return new ShellResult(0, "", "");
            }
            int chainAt = command.indexOf("-S");
            int nameAt = command.indexOf("-I") + 1;
            if (chainAt >= 0) {
                markerChainJson = command.get(chainAt + 1);
                recordedMarkerImports.add(command.get(nameAt));
            } else {
                recordedLayerImports.add(command.get(nameAt) + " <- " + command.get(nameAt + 1));
            }
            return new ShellResult(0, "", "");
        }
    }
}
