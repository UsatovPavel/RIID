package riid.integration.client_cache.auth;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.http.HttpClientConfig;

/**
 * Live Docker Hub public smoke (no tokens).
 * Requires network; gated by ENABLE_DOCKERHUB_LIVE=1.
 */
@Tag("live")
class DockerHubLiveTest {

    @Test
    void fetchManifestAndBlobFromDockerHub() throws Exception {
        assumeTrue("1".equals(System.getenv("ENABLE_DOCKERHUB_LIVE")),
                "Set ENABLE_DOCKERHUB_LIVE=1 to run live Docker Hub test");

        String repo = "library/busybox";
        String reference = "latest";

        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        HttpClientConfig cfg = HttpClientConfig.builder()
                .followRedirects(true)
                .maxRetries(1)
                .build();

        try (RegistryClientImpl client = new RegistryClientImpl(endpoint, cfg, null)) {
            ManifestResult manifest = client.fetchManifest(repo, reference);
            Manifest mf = manifest.manifest();
            assertFalse(mf.layers().isEmpty(), "layers should not be empty");

            Descriptor layer = mf.layers().getFirst();
            BlobRequest req = new BlobRequest(repo, layer.digest(), layer.size(), layer.mediaType());

            var sizeOpt = client.headBlob(repo, layer.digest());
            File tmp = Files.createTempFile("dockerhub-layer-", ".tar").toFile();
            tmp.deleteOnExit();

            BlobResult br = client.fetchBlob(req, tmp);
            assertEquals(layer.digest(), br.digest(), "digest must match manifest");
            sizeOpt.ifPresent(size -> assertEquals(size, br.size(), "size must match HEAD"));
            assertTrue(tmp.length() > 0, "downloaded file should not be empty");
        }
    }
}

