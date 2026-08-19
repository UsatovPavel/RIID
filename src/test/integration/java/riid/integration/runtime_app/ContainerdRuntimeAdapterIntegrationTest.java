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
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.core.config.TestConfigYaml;
import riid.core.config.TestRegistryConfig;

/**
 * Requires a running containerd daemon reachable at the default
 * {@code /run/containerd/containerd.sock} (root-owned; run the JVM as root, same
 * approach as {@code PortoRuntimeAdapterIntegrationTest}).
 */
@Tag("filesystem")
@Tag("local")
class ContainerdRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String CONTAINERD = "containerd";
    private static final String CTR = "ctr";

    @Test
    void downloadsImageAndImportsIntoContainerd() throws Exception {
        runIgnoreErrors(List.of(CTR, "images", "rm", "library/alpine:edge", "docker.io/library/alpine:edge"));

        ImageId loadedId;
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-containerd-", ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(), REPO, REF);
            LoadOutcome outcome = app.load(imageId, CONTAINERD);
            loadedId = outcome.imageId();
        }

        Process p = new ProcessBuilder(CTR, "images", "ls").redirectErrorStream(true).start();
        String images = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "ctr images ls failed: " + images);
        boolean found = images.contains("library/alpine:edge") || images.contains("docker.io/library/alpine:edge")
                || images.contains(loadedId.toString());
        assertTrue(found, "Expected alpine:edge in ctr images ls, got: " + images);
    }

    private static void runIgnoreErrors(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (IOException | InterruptedException ignored) {
            // ignore cleanup failures
        }
    }
}
