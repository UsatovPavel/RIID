package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.core.model.ImageId;
import riid.app.ociarchive.OciArchiveBuilder;
import riid.app.service.ImageLoadingFacade;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.SimpleRequestDispatcher;
import riid.p2p.P2PExecutor;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.runtime.adapter.IncrementalImageImport;
import riid.runtime.adapter.PortoRuntimeAdapter;
import riid.runtime.adapter.RuntimeId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("filesystem")
@Tag("local")
@Tag("porto")
class PortoRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String PODMAN = "podman";
    private static final String PORTOCTL = "portoctl";
    private static final String LAYER = "layer";

    @Test
    void downloadsImageAndLoadsIntoPorto() throws Exception {
        List<String> before = listLayers();

        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-porto-", ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));
        ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(), REPO, REF);

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            app.load(imageId, RuntimeId.PORTO);
        }

        List<String> after = listLayers();
        List<String> newLayers = new ArrayList<>(after);
        newLayers.removeAll(before);
        assertTrue(!newLayers.isEmpty(), "Expected new layers after load, got: " + after);

        for (String layer : newLayers) {
            runIgnoreErrors(List.of(PORTOCTL, LAYER, "-R", layer));
        }
    }

    /**
     * Synthetic two-layer OCI archive (no registry involved) - exercises
     * {@code PortoRuntimeAdapter}'s per-layer import path (importLayersSeparately):
     * each layer becomes its own {@code riid-layer-<digest>} Porto layer, and a
     * marker layer named after the archive carries the ordered (top-first) layer
     * chain as its private value.
     */
    @Test
    void importsTwoLayerOciArchivePerLayer() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path workDir = TestPaths.tempDir(fs, TestPaths.DEFAULT_BASE_DIR, "porto-per-layer-");
        Path archive = SyntheticOciArchives.buildTwoLayerArchive(workDir);

        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        String layerName = Objects.requireNonNull(archive.getFileName(), "archive has no file name").toString();
        runIgnoreErrors(List.of(PORTOCTL, LAYER, "-R", layerName));

        List<String> before = listLayers();
        adapter.importImage(archive);
        try {
            List<String> layers = listLayers();
            long perLayerCount = layers.stream().filter(name -> name.startsWith("riid-layer-")).count();
            assertTrue(perLayerCount >= 2, "Expected at least 2 riid-layer-* entries, got: " + layers);
            assertTrue(layers.contains(layerName), "Expected marker layer " + layerName + " in: " + layers);

            Process p = new ProcessBuilder(PORTOCTL, LAYER, "-G", layerName).redirectErrorStream(true).start();
            String chain = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, p.waitFor(), "portoctl layer -G failed: " + chain);
            assertTrue(chain.startsWith("[") && chain.contains("riid-layer-"),
                    "Expected JSON layer-name chain as private value, got: " + chain);
        } finally {
            // Remove the marker layer *and* every riid-layer-<digest> this run
            // created - leaving them behind would make the next run silently
            // exercise the reuse path instead of the import path.
            List<String> created = new ArrayList<>(listLayers());
            created.removeAll(before);
            for (String layer : created) {
                runIgnoreErrors(List.of(PORTOCTL, LAYER, "-R", layer));
            }
        }
    }

    /**
     * Prefix import against real portoctl: layers are fed one at a time, the image
     * marker appears only after the last one, and its private value carries the
     * chain top-first.
     */
    @Test
    void importsLayersIncrementally() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path workDir = TestPaths.tempDir(fs, TestPaths.DEFAULT_BASE_DIR, "porto-incremental-");
        SyntheticOciArchives.buildTwoLayerArchive(workDir);
        Path layoutDir = SyntheticOciArchives.layoutDir(workDir);
        Manifest manifest = readManifest(layoutDir);
        String imageName = "riid-incremental-" + System.nanoTime();

        List<String> before = listLayers();
        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        assertTrue(adapter.supportsIncrementalImport(manifest), "a two-layer image must take the incremental path");

        try (IncrementalImageImport session = adapter.beginIncrementalImport(imageName, manifest)) {
            for (Descriptor layer : manifest.layers()) {
                session.importLayer(layer, blobPath(layoutDir, layer));
                assertFalse(listLayers().contains(imageName), "image must not be addressable before finish()");
            }
            session.finish();
        }

        try {
            List<String> layers = listLayers();
            assertTrue(layers.contains(imageName), "Expected marker layer " + imageName + " in: " + layers);
            for (Descriptor layer : manifest.layers()) {
                String expected = "riid-layer-" + layer.digest().substring("sha256:".length());
                assertTrue(layers.contains(expected), "Expected per-layer entry " + expected + " in: " + layers);
            }

            Process p = new ProcessBuilder(PORTOCTL, LAYER, "-G", imageName).redirectErrorStream(true).start();
            String chain = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, p.waitFor(), "portoctl layer -G failed: " + chain);
            String topLayer = manifest.layers().getLast().digest().substring("sha256:".length());
            assertTrue(chain.startsWith("[\"riid-layer-" + topLayer + "\""),
                    "Chain must start with the top layer, got: " + chain);
        } finally {
            List<String> created = new ArrayList<>(listLayers());
            created.removeAll(before);
            for (String layer : created) {
                runIgnoreErrors(List.of(PORTOCTL, LAYER, "-R", layer));
            }
        }
    }

    private static Manifest readManifest(Path layoutDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode index = mapper.readTree(layoutDir.resolve("index.json").toFile());
        String digest = index.path("manifests").get(0).path("digest").asText().substring("sha256:".length());
        return mapper.readValue(layoutDir.resolve("blobs").resolve("sha256").resolve(digest).toFile(), Manifest.class);
    }

    private static Path blobPath(Path layoutDir, Descriptor layer) {
        return layoutDir.resolve("blobs").resolve("sha256").resolve(layer.digest().substring("sha256:".length()));
    }

    @Test
    @Tag("local")
    void downloadsViaRiidAndExportsRootfsTar() throws Exception {
        RegistryEndpoint endpoint = TestRegistryConfig.endpointWithOptionalEnvCredentials();
        HostFilesystem fs = new NioHostFilesystem();
        try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
                RegistryClientImpl client = new RegistryClientImpl(endpoint, new HttpClientConfig())) {
            RequestDispatcher dispatcher = new SimpleRequestDispatcher(client, cache, new P2PExecutor.NoOp(), fs);
            OciArchiveBuilder builder = new OciArchiveBuilder(dispatcher, fs);
            ImageId imageId = ImageId.fromRegistry(endpoint.registryName(), REPO, REF);
            var manifestResult = client.fetchManifest(imageId.name(), imageId.reference());
            PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
            Path outTar = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "porto-rootfs-", ".tar");
            builder.withArchive(imageId, manifestResult, archivePath -> {
                adapter.exportRootfsTar(archivePath, outTar);
                return null;
            });
            if (!fs.exists(outTar)) {
                throw new IllegalStateException("Rootfs tar was not created: " + outTar);
            }
        }
    }

    @Test
    @Tag("local")
    void exportsRootfsAndLoadsViaPortoctl() throws Exception {
        String image = System.getenv().getOrDefault("PODMAN_IMAGE", "alpine:latest");
        ensurePodmanImage(image);

        HostFilesystem fs = new NioHostFilesystem();
        Path tar = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "rootfs-", ".tar");
        String containerName = "riid-rootfs-" + System.nanoTime();
        String layerName = "rootfs-" + System.nanoTime();

        run(List.of(PODMAN, "create", "--name", containerName, image));
        try {
            run(List.of(PODMAN, "export", containerName, "-o", tar.toString()));
        } finally {
            runIgnoreErrors(List.of(PODMAN, "rm", containerName));
        }

        run(List.of(PORTOCTL, LAYER, "-I", layerName, tar.toString()));

        List<String> layers = listLayers();
        assertTrue(layers.contains(layerName), "Expected new layer " + layerName + " in: " + layers);
        runIgnoreErrors(List.of(PORTOCTL, LAYER, "-R", layerName));
    }

    private static List<String> listLayers() throws Exception {
        Process p = new ProcessBuilder(PORTOCTL, LAYER, "-L").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "portoctl layer -L failed: " + out);
        return out.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    private static void ensurePodmanImage(String image) throws Exception {
        if (imageExists(image)) {
            return;
        }
        run(List.of(PODMAN, "pull", image));
    }

    private static boolean imageExists(String image) throws Exception {
        Process p = new ProcessBuilder(PODMAN, "image", "exists", image).redirectErrorStream(true).start();
        int code = p.waitFor();
        return code == 0;
    }

    private static void runIgnoreErrors(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (IOException | InterruptedException ignored) {
            // ignore cleanup failures
        }
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Command failed: " + cmd + " -> " + code + " output: " + out);
        }
    }
}
