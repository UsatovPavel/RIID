package riid.integration.runtime_app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.core.model.ImageId;
import riid.app.service.ImageLoadingFacade;
import riid.app.service.LoadOutcome;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.config.TestRegistryConfig;
import riid.runtime.adapter.RuntimeId;

@Tag("filesystem")
@Tag("local")
class PodmanRuntimeAdapterIntegrationTest {

    /**
     * Regression: OCI archive must match manifest mediaType for {@code podman load}
     * (~10 MiB official image).
     */
    @Test
    void loadsJobberIntoPodman() throws Exception {
        PodmanRuntimeIntegrationSupport.rmiJobberIgnoreErrors();
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = PodmanRuntimeIntegrationSupport.writeDockerHubConfig(fs);
        ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(),
                PodmanRuntimeIntegrationSupport.REPO_JOBBER, PodmanRuntimeIntegrationSupport.REF_LATEST);
        ImageId loadedId;
        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            LoadOutcome outcome = app.load(imageId, RuntimeId.PODMAN);
            loadedId = outcome.imageId();
        }
        String images = PodmanRuntimeIntegrationSupport.podmanImages();
        boolean found = images.contains(loadedId.toString()) || images.contains("jobber")
                || images.contains("library/jobber");
        assertTrue(found, "Expected jobber in podman images, got: " + images);
    }

    @Test
    void loadsAlpineEdgeAndRuns() throws Exception {
        PodmanRuntimeIntegrationSupport.rmiAlpineEdgeIgnoreErrors();
        HostFilesystem fs = new NioHostFilesystem();
        Path configPath = PodmanRuntimeIntegrationSupport.writeDockerHubConfig(fs);
        ImageId imageId = ImageId.fromRegistry(TestRegistryConfig.registryName(),
                PodmanRuntimeIntegrationSupport.REPO_ALPINE, PodmanRuntimeIntegrationSupport.REF_EDGE);
        ImageId loadedId;
        try (ImageLoadingFacade app = ImageLoadingFacade.createFromConfig(configPath)) {
            LoadOutcome outcome = app.load(imageId, RuntimeId.PODMAN);
            loadedId = outcome.imageId();
        }
        String images = PodmanRuntimeIntegrationSupport.podmanImages();
        boolean found = images.contains(loadedId.toString()) || images.contains("alpine:edge")
                || images.contains("docker.io/library/alpine:edge") || images.contains("library/alpine");
        assertTrue(found, "Expected alpine:edge in podman images, got: " + images);
        PodmanRuntimeIntegrationSupport.runTrivialContainer(loadedId.toString());
    }
}
