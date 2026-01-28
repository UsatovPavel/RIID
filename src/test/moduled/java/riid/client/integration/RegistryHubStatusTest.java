package riid.client.integration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke against Docker Hub registry API (public endpoints).
 */
class RegistryHubStatusTest {

    private static final String HUB = "https://registry-1.docker.io";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void pingRequiresAuth() throws Exception {
        int status = status("/v2/");
        assertEquals(401, status, "Expected 401 from /v2/ without auth");
    }

    @Test
    void missingManifestIsAuthOrNotFound() throws Exception {
        int status = status("/v2/nonexistent-repo-12345/manifests/latest");
        assertTrue(Set.of(401, 404).contains(status),
                "Expected 401 or 404 from missing manifest, got " + status);
    }

    private int status(String path) throws Exception {
        Exception last = null;
        for (int i = 0; i < 5; i++) {
            try {
                HttpResponse<Void> resp = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(HUB + path))
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
        throw new IllegalStateException("No response from Hub for " + path);
    }
}


