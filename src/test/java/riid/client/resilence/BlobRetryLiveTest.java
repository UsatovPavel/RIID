package riid.client.resilence;

import org.junit.jupiter.api.Test;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry test against real Docker Hub.
 * Requires internet access.
 */
public class BlobRetryLiveTest {

    @Test
    void retryAfterConnectionDrop() throws Exception {
        RegistryEndpoint hub = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        HttpClientConfig cfg = new HttpClientConfig();
        try (var client = new RegistryClientImpl(hub, cfg, null)) {

        String repo = "library/alpine";
        String ref = "edge";
        String scope = "repository:library/alpine:pull";

        var manifestResult = client.fetchManifest(repo, ref);
        var layer = manifestResult.manifest().layers().getFirst();

        BlobRequest req = new BlobRequest(repo, layer.digest(), layer.size(), layer.mediaType());

        File tmp = Files.createTempFile("alpine-layer-retry", ".tar").toFile();
        tmp.deleteOnExit();

        // обычный GET; при сбое сработает retry внутри клиента
        BlobResult result = client.fetchBlob(req, tmp);

        assertEquals(layer.digest(), result.digest(), "Digest должен совпадать");
        assertEquals(layer.size(), result.size(), "Размер должен совпадать");
        assertTrue(tmp.length() > 0, "Файл должен быть не пустой");
        }
    }
}
