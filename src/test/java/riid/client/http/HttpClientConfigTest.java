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
        assertEquals(Duration.ofSeconds(30), cfg.requestTimeout());
        assertEquals(2, cfg.maxRetries());
        assertEquals(Duration.ofMillis(200), cfg.initialBackoff());
        assertEquals(Duration.ofSeconds(2), cfg.maxBackoff());
        assertEquals("riid-registry-client", cfg.userAgent());
    }

    @Test
    void negativeMaxRetriesThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                HttpClientConfig.builder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .requestTimeout(Duration.ofSeconds(1))
                        .maxRetries(-1)
                        .initialBackoff(Duration.ofMillis(100))
                        .maxBackoff(Duration.ofMillis(200))
                        .retryIdempotentOnly(true)
                        .userAgent("ua")
                        .followRedirects(true)
                        .build());
    }

    @Test
    void builderPreservesValues() {
        HttpClientConfig cfg = HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(3))
                .maxRetries(5)
                .initialBackoff(Duration.ofMillis(150))
                .maxBackoff(Duration.ofMillis(900))
                .retryIdempotentOnly(false)
                .userAgent("custom")
                .followRedirects(false)
                .build();
        HttpClientConfig copy = cfg.toBuilder().build();
        assertEquals(cfg.connectTimeout(), copy.connectTimeout());
        assertEquals(cfg.requestTimeout(), copy.requestTimeout());
        assertEquals(cfg.maxRetries(), copy.maxRetries());
        assertEquals(cfg.initialBackoff(), copy.initialBackoff());
        assertEquals(cfg.maxBackoff(), copy.maxBackoff());
        assertEquals(cfg.retryIdempotentOnly(), copy.retryIdempotentOnly());
        assertEquals(cfg.userAgent(), copy.userAgent());
        assertEquals(cfg.followRedirects(), copy.followRedirects());
    }
}

