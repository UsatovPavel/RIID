package riid.client.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.cache.auth.TokenCache;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientException;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.service.AuthService;
import riid.client.service.BlobService;
import riid.client.service.ManifestService;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

/**
 * Live smoke against Docker Hub for alpine:edge.
 * Requires internet access; no docker-compose.
 */
public class RegistryLiveTest {

    private static final RegistryEndpoint DOCKER_HUB = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
    private static final String REPO = "library/alpine";
    private static final String REF = "edge";
    private static final String SCOPE = "repository:library/alpine:pull";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClientConfig httpConfig = new HttpClientConfig();
    private final HttpClient httpClient = HttpClientFactory.create(httpConfig);
    private final HttpExecutor http = new HttpExecutor(httpClient, httpConfig);
    private final AuthService authService = new AuthService(http, mapper, new TokenCache());
    private final ManifestService manifestService = new ManifestService(http, authService, mapper);
    private final BlobService blobService = new BlobService(http, authService);
    private final HostFilesystem fs = new NioHostFilesystem();

    @Tag("filesystem")
    @Test
    void fetchManifestAndFirstLayer() throws Exception {
        // /v2/ via manifest fetch (auth flow inside)
        ManifestResult manifest = manifestService.fetchManifest(DOCKER_HUB, REPO, REF, SCOPE);
        assertFalse(manifest.manifest().layers().isEmpty(), "layers should not be empty");

        var layer = manifest.manifest().layers().getFirst();
        BlobRequest req = new BlobRequest(REPO, layer.digest(), layer.size(), layer.mediaType());

        // HEAD
        Optional<Long> sizeOpt = blobService.headBlob(DOCKER_HUB, REPO, layer.digest(), SCOPE);
        assertTrue(sizeOpt.isPresent(), "blob HEAD should return size");

        // GET blob
        File tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "alpine-layer", ".tar").toFile();
        tmp.deleteOnExit();
        BlobResult result = blobService.fetchBlob(DOCKER_HUB, req, tmp, SCOPE);

        assertEquals(layer.digest(), result.digest(), "digest must match manifest");
        assertEquals(sizeOpt.get(), result.size(), "size must match HEAD");
        assertTrue(tmp.length() > 0, "downloaded file should not be empty");
    }

    @Test
    void missingManifestReturns404() {
        assertThrows(ClientException.class,
                () -> manifestService.fetchManifest(DOCKER_HUB, REPO, "definitely-missing-tag-zzz", SCOPE),
                "missing tag should raise ClientException (404)");
    }
}

