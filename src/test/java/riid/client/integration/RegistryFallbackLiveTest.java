package riid.client.integration;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Live fallback check: if first registry is unreachable, client can fetch from a second one.
 * Uses public Docker Hub as the reachable endpoint.
 */
class RegistryFallbackLiveTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryFallbackLiveTest.class);

    private static final RegistryEndpoint BAD = new RegistryEndpoint("http", "127.0.0.1", 65500, null);
    private static final RegistryEndpoint HUB = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
    private static final String REPO = "library/busybox";
    private static final String REF = "latest";

    private static final HttpClientConfig CFG = HttpClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(8))
            .maxRetries(1)
            .initialBackoff(Duration.ofMillis(100))
            .maxBackoff(Duration.ofMillis(200))
            .retryIdempotentOnly(true)
            .userAgent("riid-fallback-test")
            .followRedirects(true)
            .build();

    @Test
    void fallsBackWhenFirstRegistryUnavailable() {
        RegistryClient badClient = new RegistryClientImpl(BAD, CFG, null);
        RegistryClient hubClient = new RegistryClientImpl(HUB, CFG, null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> badClient.fetchManifest(REPO, REF));
        LOGGER.info("Fallback: first registry failed with {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
        Throwable root = rootCause(ex);
        LOGGER.info("Fallback: root cause {}: {}", root.getClass().getSimpleName(), root.getMessage());

        ManifestResult manifest = hubClient.fetchManifest(REPO, REF);
        assertFalse(manifest.manifest().layers().isEmpty(), "manifest should contain layers");
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }
}


