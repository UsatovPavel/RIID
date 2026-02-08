package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import riid.app.ImageId;
import riid.app.ImageLoadingFacade;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;
import riid.app.ociarchive.OciArchiveBuilder;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.RequestDispatcher;
import riid.p2p.P2PExecutor;
import riid.runtime.PortoRuntimeAdapter;

@Tag("filesystem")
@Tag("local")
@EnabledIfEnvironmentVariable(named = "PORTO_INTEGRATION", matches = ".*")
class PortoRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String PODMAN = "podman";

    @Test
    void downloadsImageAndLoadsIntoPorto() throws Exception {
        List<String> before = listLayers();

        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
        String configYaml = """
                client:
                  http:
                    connectTimeout: PT5S
                    requestTimeout: PT10S
                    maxRetries: 2
                    retryIdempotentOnly: true
                    followRedirects: true
                    initialBackoff: PT0.2S
                    maxBackoff: PT2S
                  auth:
                    defaultTokenTtlSeconds: 600
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 3
                app:
                  tempDirectory: "build/test-fs"
                """;
        fs.writeString(configPath, configYaml);

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            ImageId imageId = ImageId.fromRegistry("registry-1.docker.io", REPO, REF);
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
    @EnabledIfEnvironmentVariable(named = "PORTO_INTEGRATION", matches = ".*")
    void downloadsViaRiidAndExportsRootfsTar() throws Exception {
        var endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        HostFilesystem fs = new NioHostFilesystem();
        try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
             riid.client.api.RegistryClientImpl client =
                     new riid.client.api.RegistryClientImpl(endpoint, new HttpClientConfig(), cache)) {
            RequestDispatcher dispatcher = new riid.dispatcher.SimpleRequestDispatcher(
                    client, cache, new P2PExecutor.NoOp(), fs);
            OciArchiveBuilder builder = new OciArchiveBuilder(dispatcher, fs);
            ImageId imageId = ImageId.fromRegistry(endpoint.registryName(), REPO, REF);
            System.out.println("RIID: fetching manifest for " + imageId);
            var manifest = client.fetchManifest(imageId.name(), imageId.reference());
            System.out.println("RIID: manifest digest=" + manifest.digest());
            PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
            Path outTar = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "porto-rootfs-", ".tar");
            builder.withArchive(imageId, manifest, archivePath -> {
                long size = java.nio.file.Files.size(archivePath);
                System.out.println("RIID: OCI archive path=" + archivePath + " size=" + size);
                adapter.exportRootfsTar(archivePath, outTar);
                return null;
            });
            if (!fs.exists(outTar)) {
                throw new IllegalStateException("Rootfs tar was not created: " + outTar);
            }
            System.out.println("rootfsTar=" + outTar.toAbsolutePath());
        }
    }

    @Test
    @Tag("local")
    @EnabledIfEnvironmentVariable(named = "PORTO_INTEGRATION", matches = ".*")
    void exportsRootfsAndLoadsViaPortoctl() throws Exception {
        String image = System.getenv().getOrDefault("PODMAN_IMAGE", "alpine:latest");
        if (!imageExists(image)) {
            throw new IllegalStateException("Podman image not found locally: " + image
                    + " (pre-pull or set PODMAN_IMAGE)");
        }

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
