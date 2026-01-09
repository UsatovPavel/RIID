package riid.client.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import riid.cache.CacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.Manifest;
import riid.client.http.HttpClientConfig;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress/retry test (step 9.1).
 *
 * Not run in CI (tagged "stress"). Run locally:
 *   ./gradlew test --tests 'riid.client.integration.StressTest' -PincludeStress
 *
 * Scenarios:
 * - 100 мелких образов 740.2 kB (busybox:latest) скачиваются параллельно, проверка digest/size.
 */
@Tag("stress")
@SuppressWarnings("deprecation")
public class StressTest {

    private static RegistryClient CLIENT;
    private static final String REPO = "library/busybox";
    private static final String REF = "latest";

    @BeforeAll
    static void setup() {
        RegistryEndpoint hub = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        HttpClientConfig cfg = new HttpClientConfig(
                null, null, 2, null, null, true, null, true);
        CLIENT = new RegistryClientImpl(hub, cfg, (CacheAdapter) null);
    }

    @Test
    void downloadManyInParallel() throws Exception {
        Manifest manifest = CLIENT.fetchManifest(REPO, REF).manifest();
        var layer = manifest.layers().getFirst(); // tiny layer in busybox
        BlobRequest req = new BlobRequest(REPO, layer.digest(), layer.size(), layer.mediaType());

        int count = 1000;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<File>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        File tmp = Files.createTempFile(
                                "busybox-layer-" + Thread.currentThread().getId(),
                                ".tar")
                                .toFile();
                        var res = CLIENT.fetchBlob(req, tmp);
                        assertEquals(layer.digest(), res.digest());
                        assertTrue(tmp.length() > 0);
                        return tmp;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            for (var f : futures) {
                f.join();
            }
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting executor termination", ie);
        }
    }
}

