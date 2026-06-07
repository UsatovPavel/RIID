package riid.client.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientConfigTest {

    @Test
    void defaultsFillNulls() {
        HttpClientConfig cfg = HttpClientConfig.builder().build();
        assertEquals(Duration.ofSeconds(5), cfg.connectTimeout());
        assertEquals(Duration.ofMinutes(30), cfg.requestTimeout());
        assertEquals(Duration.ofMinutes(2), cfg.imageTimeoutMin());
        assertEquals(Duration.ofMinutes(30), cfg.imageTimeoutMax());
        assertEquals(2, cfg.maxRetries());
        assertEquals(Duration.ofMillis(200), cfg.initialBackoff());
        assertEquals(Duration.ofSeconds(2), cfg.maxBackoff());
        assertEquals("riid-registry-client", cfg.userAgent());
    }

    @Test
    void negativeMaxRetriesThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpClientConfig.builder().connectTimeout(Duration.ofSeconds(1))
                        .requestTimeout(Duration.ofSeconds(1)).maxRetries(-1).initialBackoff(Duration.ofMillis(100))
                        .maxBackoff(Duration.ofMillis(200)).retryIdempotentOnly(true).userAgent("ua")
                        .followRedirects(true).build());
    }

    @Test
    void builderPreservesValues() {
        HttpClientConfig cfg = HttpClientConfig.builder().connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(3)).imageTimeoutMin(Duration.ofSeconds(4))
                .imageTimeoutMax(Duration.ofSeconds(10)).maxRetries(5).initialBackoff(Duration.ofMillis(150))
                .maxBackoff(Duration.ofMillis(900)).retryIdempotentOnly(false).userAgent("custom")
                .followRedirects(false).build();
        HttpClientConfig copy = cfg.toBuilder().build();
        assertEquals(cfg.connectTimeout(), copy.connectTimeout());
        assertEquals(cfg.requestTimeout(), copy.requestTimeout());
        assertEquals(cfg.imageTimeoutMin(), copy.imageTimeoutMin());
        assertEquals(cfg.imageTimeoutMax(), copy.imageTimeoutMax());
        assertEquals(cfg.maxRetries(), copy.maxRetries());
        assertEquals(cfg.initialBackoff(), copy.initialBackoff());
        assertEquals(cfg.maxBackoff(), copy.maxBackoff());
        assertEquals(cfg.retryIdempotentOnly(), copy.retryIdempotentOnly());
        assertEquals(cfg.userAgent(), copy.userAgent());
        assertEquals(cfg.followRedirects(), copy.followRedirects());
    }

    @Test
    void timeoutForSizeBytesInterpolatesAndCaps() {
        HttpClientConfig cfg = HttpClientConfig.builder().imageTimeoutMin(Duration.ofMinutes(1))
                .imageTimeoutMax(Duration.ofMinutes(30)).build();
        assertEquals(Duration.ofMinutes(1), cfg.timeoutForSizeBytes(0));
        assertEquals(Duration.ofMinutes(30), cfg.timeoutForSizeBytes(15L * 1024 * 1024 * 1024));
        assertEquals(Duration.ofMinutes(30), cfg.timeoutForSizeBytes(30L * 1024 * 1024 * 1024));
    }
}
