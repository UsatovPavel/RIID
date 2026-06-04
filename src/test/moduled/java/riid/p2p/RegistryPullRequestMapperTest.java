package riid.p2p;

import org.junit.jupiter.api.Test;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.p2p.dragonfly.RegistryPullRequestMapper;
import ru.hse.dragonfly.puller.registry.RegistryAuth;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryPullRequestMapperTest {
    private static final String HTTPS_SCHEME = "https";
    private static final String REGISTRY_HOST = "registry.example.com";
    private static final Path DEFAULT_OUTPUT_PATH = Path.of("/tmp/p2p.bin");
    private static final String REPO = "library/alpine";
    private static final String DIGEST = "sha256:" + "b".repeat(64);

    @Test
    void mapsEndpointRepositoryDigestAndOutputPath() {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, 5000, null);
        Path outputPath = DEFAULT_OUTPUT_PATH;

        var request = riid.p2p.dragonfly.RegistryPullRequestMapper.map(endpoint, REPO, ImageDigest.parse(DIGEST),
                outputPath);

        assertEquals("https://registry.example.com:5000", request.registry());
        assertEquals(REPO, request.repository());
        assertEquals(DIGEST, request.digest());
        assertNull(request.tag());
        assertEquals(outputPath, request.outputPath());
    }

    @Test
    void mapsBasicCredentialsToRegistryAuth() {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1,
                Credentials.basic("user", "secret"));

        var request = riid.p2p.dragonfly.RegistryPullRequestMapper.map(endpoint, REPO, ImageDigest.parse(DIGEST),
                DEFAULT_OUTPUT_PATH);

        RegistryAuth.Basic auth = assertInstanceOf(RegistryAuth.Basic.class, request.auth());
        assertEquals("user", auth.username());
        assertEquals("secret", auth.password());
    }

    @Test
    void mapsIdentityTokenToJwtAuth() {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1,
                Credentials.identityToken("jwt-token"));

        var request = riid.p2p.dragonfly.RegistryPullRequestMapper.map(endpoint, REPO, ImageDigest.parse(DIGEST),
                DEFAULT_OUTPUT_PATH);

        RegistryAuth.Bearer auth = assertInstanceOf(RegistryAuth.Bearer.class, request.auth());
        assertEquals("jwt-token", auth.token());
    }

    @Test
    void mapsMissingCredentialsToNoneAuth() {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);

        var request = riid.p2p.dragonfly.RegistryPullRequestMapper.map(endpoint, REPO, ImageDigest.parse(DIGEST),
                DEFAULT_OUTPUT_PATH);

        assertInstanceOf(RegistryAuth.None.class, request.auth());
    }

    @Test
    void rejectsNullInputs() {
        RegistryEndpoint endpoint = new RegistryEndpoint(HTTPS_SCHEME, REGISTRY_HOST, -1, null);
        ImageDigest digest = ImageDigest.parse(DIGEST);
        Path outputPath = DEFAULT_OUTPUT_PATH;

        assertThrows(NullPointerException.class,
                () -> riid.p2p.dragonfly.RegistryPullRequestMapper.map(null, REPO, digest, outputPath));
        assertThrows(NullPointerException.class,
                () -> riid.p2p.dragonfly.RegistryPullRequestMapper.map(endpoint, null, digest, outputPath));
        assertThrows(NullPointerException.class,
                () -> riid.p2p.dragonfly.RegistryPullRequestMapper.map(endpoint, REPO, null, outputPath));
        assertThrows(NullPointerException.class, () -> RegistryPullRequestMapper.map(endpoint, REPO, digest, null));
    }
}
