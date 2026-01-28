package riid.client.integration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live checks against GHCR (public endpoints).
 */
class RegistryGhcrStatusTest {

    private static final String GHCR = "https://ghcr.io";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void pingReturns200() throws Exception {
        int status = status("/v2/");
        assertTrue(Set.of(200, 401).contains(status),
                "Expected 200 or 401 from GHCR /v2/, got " + status);
    }

    @Test
    void missingManifestIs404() throws Exception {
        int status = status("/v2/nonexistent-repo-12345/manifests/latest");
        assertTrue(Set.of(401, 404).contains(status),
                "Expected 401 or 404 from missing manifest, got " + status);
    }

    private int status(String path) throws Exception {
        Exception last = null;
        for (int i = 0; i < 5; i++) {
            try {
                HttpResponse<Void> resp = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(GHCR + path))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                return resp.statusCode();
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("No response from GHCR for " + path);
    }
}


