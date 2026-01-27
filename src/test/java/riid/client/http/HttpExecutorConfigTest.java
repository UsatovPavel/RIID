package riid.client.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpExecutorConfigTest {

    @Test
    void throwsWhenNonIdempotentRetryRequested() {
        HttpClientConfig cfg = HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(1)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(200))
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build();
        HttpExecutor exec = new HttpExecutor(new org.eclipse.jetty.client.HttpClient(), cfg);

        assertThrows(IllegalStateException.class, () -> exec.shouldRetry(503, 1, false));
        assertThrows(IllegalStateException.class, () -> exec.shouldRetryIOException(1, false));
    }
}

