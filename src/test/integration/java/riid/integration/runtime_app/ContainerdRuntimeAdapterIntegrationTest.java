package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

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
import riid.runtime.adapter.ContainerdRuntimeAdapter;

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
    private static final String CTR = ContainerdRuntimeAdapter.CTR_BIN;

    @Test
    void downloadsImageAndImportsIntoContainerd() throws Exception {
        runIgnoreErrors(List.of(CTR, "images", "rm", "library/alpine:edge", "docker.io/library/alpine:edge"));

        ImageId loadedId = loadAlpineEdge("config-containerd-");

        String images = run(List.of(CTR, "images", "ls"));
        boolean found = images.contains("library/alpine:edge") || images.contains("docker.io/library/alpine:edge")
                || images.contains(loadedId.toString());
        assertTrue(found, "Expected alpine:edge in ctr images ls, got: " + images);
    }

    @Test
    void loadsAlpineEdgeAndRuns() throws Exception {
        runIgnoreErrors(List.of(CTR, "images", "rm", "library/alpine:edge", "docker.io/library/alpine:edge"));

        ImageId loadedId = loadAlpineEdge("config-containerd-run-");
        String imageRef = findImportedImageRef(loadedId);
        String containerId = "riid-containerd-test-" + System.nanoTime();

        try {
            run(List.of(CTR, "run", "--rm", imageRef, containerId, "true"));
        } finally {
            runIgnoreErrors(List.of(CTR, "task", "rm", "-f", containerId));
            runIgnoreErrors(List.of(CTR, "container", "rm", containerId));
        }
    }

    private static ImageId loadAlpineEdge(String configPrefix) throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, configPrefix, ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));

        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(), REPO, REF);
            LoadOutcome outcome = app.load(imageId, CONTAINERD);
            return outcome.imageId();
        }
    }

    private static String findImportedImageRef(ImageId loadedId) throws Exception {
        String out = run(List.of(CTR, "images", "ls", "-q"));
        for (String line : out.lines().map(String::trim).toList()) {
            if (line.equals("library/alpine:edge") || line.equals("docker.io/library/alpine:edge")
                    || line.equals(loadedId.toString())) {
                return line;
            }
        }
        throw new IllegalStateException("Expected alpine:edge in ctr images ls -q, got: " + out);
    }

    private static String run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed: " + cmd + " -> " + code + " output: " + out);
        }
        return out;
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
