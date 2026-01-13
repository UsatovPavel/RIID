package riid.client.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpExecutorConfigTest {

    @Test
    void throwsWhenNonIdempotentRetryRequested() {
        HttpClientConfig cfg = new HttpClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                true,
                "ua",
                true
        );
        HttpExecutor exec = new HttpExecutor(new org.eclipse.jetty.client.HttpClient(), cfg);

        assertThrows(IllegalStateException.class, () -> exec.shouldRetry(503, 1, false));
        assertThrows(IllegalStateException.class, () -> exec.shouldRetryIOException(1, false));
    }
}

