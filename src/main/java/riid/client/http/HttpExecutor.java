package riid.client.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thin wrapper over HttpClient with retries for idempotent GET/HEAD.
 */
public final class HttpExecutor {
    private static final List<Integer> RETRY_STATUSES = List.of(429, 502, 503, 504);

    private final HttpClient client;
    private final HttpClientConfig config;

    public HttpExecutor(HttpClient client, HttpClientConfig config) {
        this.client = Objects.requireNonNull(client);
        this.config = Objects.requireNonNull(config);
    }

    public HttpResponse<java.io.InputStream> get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(config.requestTimeout());

        headers.forEach(builder::header);
        if (config.userAgent() != null && !config.userAgent().isBlank()) {
            builder.header("User-Agent", config.userAgent());
        }

        return sendWithRetry(builder.build(), HttpResponse.BodyHandlers.ofInputStream(), true);
    }

    public HttpResponse<Void> head(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(config.requestTimeout());
        headers.forEach(builder::header);
        if (config.userAgent() != null && !config.userAgent().isBlank()) {
            builder.header("User-Agent", config.userAgent());
        }
        return sendWithRetry(builder.build(), HttpResponse.BodyHandlers.discarding(), true);
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest request,
                                             HttpResponse.BodyHandler<T> handler,
                                             boolean idempotent) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<T> resp = client.send(request, handler);
                if (shouldRetry(resp.statusCode(), attempts, idempotent)) {
                    backoff(attempts);
                    continue;
                }
                return resp;
            } catch (IOException e) {
                if (shouldRetryIOException(attempts, idempotent)) {
                    backoff(attempts);
                    continue;
                }
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private boolean shouldRetry(int status, int attempts, boolean idempotent) {
        if (attempts >= 1 + config.maxRetries()) {
            return false;
        }
        if (config.retryIdempotentOnly() && !idempotent) {
            return false;
        }
        return RETRY_STATUSES.contains(status);
    }

    private boolean shouldRetryIOException(int attempts, boolean idempotent) {
        if (attempts >= 1 + config.maxRetries()) {
            return false;
        }
        if (config.retryIdempotentOnly() && !idempotent) {
            return false;
        }
        return true;
    }

    private void backoff(int attempts) {
        long base = config.initialBackoff().toMillis();
        long max = config.maxBackoff().toMillis();
        long expo = base * (1L << Math.max(0, attempts - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base);
        long sleep = Math.min(max, expo + jitter);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static String rangeHeader(long startInclusive, Long endInclusive) {
        if (startInclusive < 0) throw new IllegalArgumentException("start must be >= 0");
        if (endInclusive != null && endInclusive < startInclusive) {
            throw new IllegalArgumentException("end must be >= start");
        }
        return endInclusive == null
                ? "bytes=%d-".formatted(startInclusive)
                : "bytes=%d-%d".formatted(startInclusive, endInclusive);
    }
}

