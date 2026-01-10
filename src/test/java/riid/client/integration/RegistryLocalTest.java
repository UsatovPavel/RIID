package riid.client.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import riid.cache.TokenCache;
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
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Integration test against a local registry:2, started via Testcontainers.
 */
@Tag("local")
@Testcontainers
public class RegistryLocalTest {

    @Container
    private static final GenericContainer<?> REGISTRY = new GenericContainer<>("registry:2")
            .withExposedPorts(5000);

    private static RegistryEndpoint LOCAL;
    private static final String REPO = "hello-world";
    private static final String REF = "latest";
    private static final String SCOPE = "repository:%s:pull".formatted(REPO);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClientConfig httpConfig = new HttpClientConfig();
    private final HttpClient httpClient = HttpClientFactory.create(httpConfig);
    private final HttpExecutor http = new HttpExecutor(httpClient, httpConfig);
    private final AuthService authService = new AuthService(http, mapper, new TokenCache());
    private final ManifestService manifestService = new ManifestService(http, authService, mapper);
    private final BlobService blobService = new BlobService(http, authService, null);

    @BeforeAll
    static void startRegistryAndSeed() throws Exception {
        REGISTRY.start();
        String host = REGISTRY.getHost();
        int port = REGISTRY.getMappedPort(5000);
        LOCAL = new RegistryEndpoint("http", host, port, null);

        // Seed registry with hello-world using host docker
        String localImage = "%s:%d/%s".formatted(host, port, REPO);
        run("docker", "pull", REPO);
        run("docker", "tag", REPO, localImage);
        run("docker", "push", localImage);
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", cmd));
        }
    }

    @Test
    void fetchManifestAndLayer() throws Exception {
        ManifestResult manifest = manifestService.fetchManifest(LOCAL, REPO, REF, SCOPE);
        Assertions.assertFalse(manifest.manifest().layers().isEmpty(), "layers should not be empty");

        var layer = manifest.manifest().layers().getFirst();
        BlobRequest req = new BlobRequest(REPO, layer.digest(), layer.size(), layer.mediaType());

        Optional<Long> sizeOpt = blobService.headBlob(LOCAL, REPO, layer.digest(), SCOPE);
        Assertions.assertTrue(sizeOpt.isPresent(), "blob HEAD should return size");

        File tmp = Files.createTempFile("local-layer", ".tar").toFile();
        tmp.deleteOnExit();
        BlobResult result = blobService.fetchBlob(LOCAL, req, tmp, SCOPE);

        Assertions.assertEquals(layer.digest(), result.digest(), "digest must match manifest");
        Assertions.assertEquals(sizeOpt.get(), result.size(), "size must match HEAD");
        Assertions.assertTrue(tmp.length() > 0, "downloaded file should not be empty");
    }

    @Test
    void manifestNotFoundReturns404() {
        Executable call = () -> manifestService.fetchManifest(LOCAL, "missing-repo", "missing", SCOPE);
        Assertions.assertThrows(ClientException.class, call, "fetching missing manifest should throw");
    }
}

