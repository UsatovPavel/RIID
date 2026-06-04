package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import riid.runtime.adapter.PortoRuntimeAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("filesystem")
@Tag("local")
@Tag("porto")
class PortoRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String PODMAN = "podman";

    @Test
    void downloadsImageAndLoadsIntoPorto() throws Exception {
        List<String> before = listLayers();

        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-porto-", ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));
        ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(), REPO, REF);

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            app.load(imageId, "porto");
        }

        List<String> after = listLayers();
        List<String> newLayers = new ArrayList<>(after);
        newLayers.removeAll(before);
        assertTrue(!newLayers.isEmpty(), "Expected new layers after load, got: " + after);

        for (String layer : newLayers) {
            runIgnoreErrors(List.of("portoctl", "layer", "-R", layer));
        }
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

        run(List.of("portoctl", "layer", "-I", layerName, tar.toString()));

        List<String> layers = listLayers();
        assertTrue(layers.contains(layerName), "Expected new layer " + layerName + " in: " + layers);
        runIgnoreErrors(List.of("portoctl", "layer", "-R", layerName));
    }

    private static List<String> listLayers() throws Exception {
        Process p = new ProcessBuilder("portoctl", "layer", "-L")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "portoctl layer -L failed: " + out);
        return out.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static void ensurePodmanImage(String image) throws Exception {
        if (imageExists(image)) {
            return;
        }
        run(List.of(PODMAN, "pull", image));
    }

    private static boolean imageExists(String image) throws Exception {
        Process p = new ProcessBuilder(PODMAN, "image", "exists", image)
                .redirectErrorStream(true)
                .start();
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
