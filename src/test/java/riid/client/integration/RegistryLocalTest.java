package riid.client.integration;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;
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

/**
 * Integration test against a local registry:2, started via Testcontainers.
 * VPN sensitive
 */
@Tag("local")
@Testcontainers
public class RegistryLocalTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REGISTRY = new GenericContainer<>("registry:2")
            .withExposedPorts(5000);

    private static RegistryEndpoint LOCAL;
    private static final String REPO = "hello-world";
    private static final String REF = "latest";
    private static final String SCOPE = "repository:%s:pull".formatted(REPO);

    private static HttpClient httpClient;
    private static HttpExecutor http;
    private static AuthService authService;
    private static ManifestService manifestService;
    private static BlobService blobService;
    private final HostFilesystem fs = new NioHostFilesystem();

    @BeforeAll
    public static void startRegistryAndSeed() throws Exception {
        REGISTRY.start();
        String host = REGISTRY.getHost();
        int port = REGISTRY.getMappedPort(5000);
        LOCAL = new RegistryEndpoint("http", host, port, null);
        HttpClientConfig httpConfig = new HttpClientConfig();
        httpClient = HttpClientFactory.create(httpConfig);
        http = new HttpExecutor(httpClient, httpConfig);
        authService = new AuthService(http, new ObjectMapper(), new TokenCache());
        manifestService = new ManifestService(http, authService, new ObjectMapper());
        blobService = new BlobService(http, authService, null);

        // Seed registry with hello-world using host docker
        String localImage = "%s:%d/%s".formatted(host, port, REPO);
        run("docker", "pull", REPO);
        run("docker", "tag", REPO, localImage);
        run("docker", "push", localImage);
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Command failed (%d): %s%nOutput:%n%s"
                    .formatted(code, String.join(" ", cmd), output));
        }
    }

    @AfterAll
    public static void stop() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
        REGISTRY.stop();
    }

    @Test
    void fetchManifestAndLayer() throws Exception {
        ManifestResult manifest = manifestService.fetchManifest(LOCAL, REPO, REF, SCOPE);
        Assertions.assertFalse(manifest.manifest().layers().isEmpty(), "layers should not be empty");

        var layer = manifest.manifest().layers().getFirst();
        BlobRequest req = new BlobRequest(REPO, layer.digest(), layer.size(), layer.mediaType());

        Optional<Long> sizeOpt = blobService.headBlob(LOCAL, REPO, layer.digest(), SCOPE);
        Assertions.assertTrue(sizeOpt.isPresent(), "blob HEAD should return size");

        File tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "local-layer", ".tar").toFile();
        tmp.deleteOnExit();
        BlobResult result = blobService.fetchBlob(LOCAL, req, tmp, SCOPE);

        Assertions.assertEquals(layer.digest(), result.digest(), "digest must match manifest");
        Assertions.assertEquals(sizeOpt.get().longValue(), result.size(), "size must match HEAD");
        Assertions.assertTrue(tmp.length() > 0, "downloaded file should not be empty");
    }

    @Test
    void manifestNotFoundReturns404() {
        Executable call = () -> manifestService.fetchManifest(LOCAL, "missing-repo", "missing", SCOPE);
        ClientException ex = Assertions.assertThrows(ClientException.class, call,
                "fetching missing manifest should throw");
        Assertions.assertNotNull(ex.getMessage());
    }
}

