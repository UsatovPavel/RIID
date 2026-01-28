package riid.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;
import riid.cache.auth.TokenCache;
import riid.client.api.ManifestResult;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientException;
import riid.client.core.model.Digests;
import riid.client.core.model.manifest.MediaTypes;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestServiceTest {

    private final RegistryEndpoint endpoint = RegistryEndpoint.https("registry-1.docker.io");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void throwsOnEmptyManifestList() {
        String template = """
                {"schemaVersion":2,"mediaType":"%s","manifests":[]}
                """.replace("\n", "%n");
        byte[] body = template.formatted(MediaTypes.OCI_IMAGE_INDEX)
                .getBytes(StandardCharsets.UTF_8);
        HttpFields.Mutable headers = HttpFields.build();
        headers.add("Content-Type", MediaTypes.OCI_IMAGE_INDEX);

        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, headers,
                new ByteArrayInputStream(body), URI.create("https://x"));

        ManifestService svc = new ManifestService(http, new NoAuth(), mapper);

        assertThrows(ClientException.class, () -> svc.fetchManifest(endpoint, "library/busybox", "latest", "scope"));
    }

    @Test
    void parsesRegularManifest() {
        String template = """
                {
                  "schemaVersion": 2,
                  "mediaType": "%s",
                  "config": {"mediaType":"application/vnd.oci.image.config.v1+json","digest":"sha256:dead","size":12},
                  "layers": [{"mediaType":"application/vnd.oci.image.layer.v1.tar","digest":"sha256:beef","size":34}]
                }
                """.replace("\n", "%n");
        byte[] body = template.formatted(MediaTypes.OCI_IMAGE_MANIFEST)
                .getBytes(StandardCharsets.UTF_8);
        HttpFields.Mutable headers = HttpFields.build();
        headers.add("Content-Type", MediaTypes.OCI_IMAGE_MANIFEST);

        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, headers,
                new ByteArrayInputStream(body), URI.create("https://x"));

        ManifestService svc = new ManifestService(http, new NoAuth(), mapper);

        ManifestResult res = svc.fetchManifest(endpoint, "library/busybox", "latest", "scope");

        assertEquals(MediaTypes.OCI_IMAGE_MANIFEST, res.mediaType());
        assertTrue(res.contentLength() > 0);
        assertEquals("sha256:" + Digests.sha256Hex(body), res.digest());
    }

    private static final class FakeHttp extends HttpExecutor {
        HttpResult<InputStream> nextGet;

        FakeHttp() {
            super(new org.eclipse.jetty.client.HttpClient(), new HttpClientConfig());
        }

        @Override
        public HttpResult<InputStream> get(URI uri, Map<String, String> headers) {
            return nextGet;
        }

        @Override
        public HttpResult<Void> head(URI uri, Map<String, String> headers) {
            throw new UnsupportedOperationException("head not used");
        }
    }

    private static final class NoAuth extends AuthService {
        NoAuth() {
            super(new FakeHttp(), new ObjectMapper(), new TokenCache(), 300);
        }

        @Override
        public Optional<String> getAuthHeader(RegistryEndpoint endpoint, String repository, String scope) {
            return Optional.empty();
        }
    }
}

