package riid.integration.runtime_app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import riid.core.fs.HostFilesystem;
import riid.core.fs.TestPaths;
import riid.core.config.TestConfigYaml;

/**
 * Shared helpers for {@link PodmanRuntimeAdapterIntegrationTest}.
 */
final class PodmanRuntimeIntegrationSupport {

    static final String PODMAN = "podman";
    static final String REPO_ALPINE = "library/alpine";
    static final String REF_EDGE = "edge";
    static final String REPO_JOBBER = "library/jobber";
    static final String REF_LATEST = "latest";

    private PodmanRuntimeIntegrationSupport() {
    }

    static Path writeDockerHubConfig(HostFilesystem fs) throws Exception {
        Path configPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-podman-", ".yaml");
        fs.writeString(configPath, TestConfigYaml.dockerHubConfigWithRuntimeTempDir(3, "build/test-fs"));
        return configPath;
    }

    static String podmanImages() throws Exception {
        Process p = new ProcessBuilder(PODMAN, "images", "--format", "{{.Repository}}:{{.Tag}}")
                .redirectErrorStream(true).start();
        String images = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("podman images failed: " + code + " " + images);
        }
        return images;
    }

    static void runTrivialContainer(String imageRef) throws Exception {
        run(List.of(PODMAN, "run", "--rm", imageRef, "true"));
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
        } catch (IOException | InterruptedException ignored) {
            // ignore cleanup failures
        }
    }

    static void rmiJobberIgnoreErrors() {
        runIgnoreErrors(List.of(PODMAN, "rmi", "-f", "jobber:latest", "docker.io/library/jobber:latest"));
    }

    static void rmiAlpineEdgeIgnoreErrors() {
        runIgnoreErrors(List.of(PODMAN, "rmi", "-f", "alpine:edge", "docker.io/library/alpine:edge"));
    }
}
