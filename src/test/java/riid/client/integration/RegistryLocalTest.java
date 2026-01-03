package riid.client.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import riid.client.auth.AuthService;
import riid.client.auth.TokenCache;
import riid.client.blob.BlobRequest;
import riid.client.blob.BlobResult;
import riid.client.blob.BlobService;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.manifest.ManifestResult;
import riid.client.manifest.ManifestService;

import java.io.File;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test against local registry:2 (requires running registry and a pushed image).
 *
 * How to run locally:
 * 1) Start registry:2:
 *    docker run -d -p 5000:5000 --name reg registry:2
 * 2) Pull/push a small image:
 *    docker pull hello-world
 *    docker tag hello-world localhost:5000/hello-world
 *    docker push localhost:5000/hello-world
 * 3) Run this test:
 *    ./gradlew test --tests 'riid.client.integration.RegistryLocalTest'
 */
public class RegistryLocalTest {

    private static final RegistryEndpoint LOCAL = new RegistryEndpoint("http", "localhost", 5000, null);
    private static final String REPO = "hello-world";
    private static final String REF = "latest";
    private static final String SCOPE = "repository:%s:pull".formatted(REPO);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClientConfig httpConfig = HttpClientConfig.builder().build();
    private final HttpClient httpClient = HttpClientFactory.create(httpConfig);
    private final HttpExecutor http = new HttpExecutor(httpClient, httpConfig);
    private final AuthService authService = new AuthService(http, mapper, new TokenCache());
    private final ManifestService manifestService = new ManifestService(http, authService, mapper);
    private final BlobService blobService = new BlobService(http, authService, null);

    @Test
    void fetchManifestAndLayer() throws Exception {
        ManifestResult manifest = manifestService.fetchManifest(LOCAL, REPO, REF, SCOPE);
        assertFalse(manifest.manifest().layers().isEmpty(), "layers should not be empty");

        var layer = manifest.manifest().layers().getFirst();
        BlobRequest req = new BlobRequest(REPO, layer.digest(), layer.size(), layer.mediaType());

        Optional<Long> sizeOpt = blobService.headBlob(LOCAL, REPO, layer.digest(), SCOPE);
        assertTrue(sizeOpt.isPresent(), "blob HEAD should return size");

        File tmp = Files.createTempFile("local-layer", ".tar").toFile();
        tmp.deleteOnExit();
        BlobResult result = blobService.fetchBlob(LOCAL, req, tmp, SCOPE);

        assertEquals(layer.digest(), result.digest(), "digest must match manifest");
        assertEquals(sizeOpt.get(), result.size(), "size must match HEAD");
        assertTrue(tmp.length() > 0, "downloaded file should not be empty");
    }
}

