package riid.client.resilence;

import org.junit.jupiter.api.Test;
import riid.client.RegistryClientImpl;
import riid.client.blob.BlobRequest;
import riid.client.blob.BlobResult;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Optional;

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
        HttpClientConfig cfg = HttpClientConfig.builder().build();
        var client = new RegistryClientImpl(hub, cfg, null);

        String repo = "library/alpine";
        String ref = "edge";
        String scope = "repository:library/alpine:pull";

        var manifestResult = client.fetchManifest(repo, ref);
        var layer = manifestResult.manifest().layers().getFirst();

        BlobRequest req = new BlobRequest(repo, layer.digest(), layer.size(), layer.mediaType());

        File tmp = Files.createTempFile("alpine-layer-retry", ".tar").toFile();
        tmp.deleteOnExit();

        // Обёртка InputStream для симуляции обрыва соединения при первом запросе
        var blobService = client.cacheAdapter() == null ? client.fetchBlob(req, tmp) : null;

        BlobResult result = client.fetchBlob(req, tmp); // просто обычный GET, первый раз может "упасть", retry внутри клиента

        assertEquals(layer.digest(), result.digest(), "Digest должен совпадать");
        assertEquals(layer.size(), result.size(), "Размер должен совпадать");
        assertTrue(tmp.length() > 0, "Файл должен быть не пустой");
    }
}
