package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.core.model.ImageId;
import riid.app.service.ImageLoadingFacade;
import riid.app.service.LoadOutcome;
import riid.app.service.RuntimeRegistry;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.api.RegistryClientImpl;
import riid.client.http.HttpClientConfig;
import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;
import riid.dispatcher.RequestDispatcher;
import riid.p2p.P2PExecutor;
import riid.runtime.DockerRuntimeAdapter;

@Tag("filesystem")
@Tag("local")
class DockerRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String DOCKER = "docker";

    @Test
    void downloadsImageAndLoadsIntoDocker() throws Exception {
        runIgnoreErrors(List.of(DOCKER, "rmi", "-f", "alpine:edge"));

        ImageId loadedId;
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-docker-", ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(), REPO, REF);
            LoadOutcome outcome = app.load(imageId, DOCKER);
            loadedId = outcome.imageId();
        }

        Process p = new ProcessBuilder(DOCKER, "images", "--format", "{{.Repository}}:{{.Tag}}")
                .redirectErrorStream(true)
                .start();
        String images = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "docker images failed: " + images);
        boolean found = images.contains("alpine:edge")
                || images.contains("docker.io/library/alpine:edge")
                || images.contains(loadedId.toString());
        assertTrue(found, "Expected alpine:edge in docker images, got: " + images);
    }

    @Test
    void oneShotLoadAndRun() throws Exception {
        var endpoint = TestRegistryConfig.endpoint();
        HostFilesystem fs = new NioHostFilesystem();
        try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
             RegistryClientImpl client =
                     new RegistryClientImpl(endpoint, new HttpClientConfig())) {
            RequestDispatcher dispatcher = new riid.dispatcher.SimpleRequestDispatcher(
                    client, cache, new P2PExecutor.NoOp(), fs);
            RuntimeRegistry registry = new RuntimeRegistry(java.util.Map.of(DOCKER, new DockerRuntimeAdapter()));
            try (ImageLoadingFacade app = new ImageLoadingFacade(
                    dispatcher,
                    registry,
                    client,
                    fs,
                    TestPaths.DEFAULT_BASE_DIR,
                    java.util.List.of())) {
                ImageId imageId = ImageId.fromRegistry(endpoint.registryName(), REPO, REF);
                ImageId loadedId = app.load(imageId, DOCKER).imageId();
                run(List.of(DOCKER, "run", "--rm", loadedId.toString(), "true"));
            }
        }
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed: " + cmd + " -> " + code + " output: " + out);
        }
    }

    private static void runIgnoreErrors(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ignored) {
            // ignore cleanup failures
        }
    }
}
