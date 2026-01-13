package riid.client.http;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin wrapper over Jetty HttpClient with retries for idempotent GET/HEAD.
 */
public class HttpExecutor {
    private static final String METHOD_HEAD = "HEAD";
    private static final List<Integer> RETRY_STATUSES = List.of(429, 502, 503, 504);

    private final HttpClient client;
    private final HttpClientConfig config;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Jetty client lifecycle managed by caller")
    @SuppressWarnings("PMD.EI_EXPOSE_REP2")
    public HttpExecutor(HttpClient client, HttpClientConfig config) {
        this.client = Objects.requireNonNull(client);
        this.config = Objects.requireNonNull(config);
    }

    public HttpResult<InputStream> get(URI uri, Map<String, String> headers) {
        return sendWithRetry("GET", uri, headers, true);
    }

    public HttpResult<Void> head(URI uri, Map<String, String> headers) {
        HttpResult<InputStream> resp = sendWithRetry("HEAD", uri, headers, true);
        return new HttpResult<>(resp.statusCode(), resp.headers(), null, resp.uri());
    }

    private HttpResult<InputStream> sendWithRetry(String method,
                                                  URI uri,
                                                  Map<String, String> headers,
                                                  boolean idempotent) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResult<InputStream> resp = execute(method, uri, headers);
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
            }
        }
    }

    private HttpResult<InputStream> execute(String method,
                                            URI uri,
                                            Map<String, String> headers) throws IOException {
        if (METHOD_HEAD.equalsIgnoreCase(method)) {
            try {
                Request req = client.newRequest(uri)
                        .method(METHOD_HEAD)
                        .timeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .headers(h -> {
                            headers.forEach(h::add);
                            applyUserAgent(h, headers);
                        });
                ContentResponse response = req.send();
                HttpFields httpHeaders = response.getHeaders();
                return new HttpResult<>(response.getStatus(), httpHeaders, null, uri);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Jetty HEAD interrupted", ie);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("Jetty HEAD failed", e);
            }
        }

        @SuppressWarnings("PMD.CloseResource")
        InputStreamResponseListener listener = new InputStreamResponseListener();
        Request request = client.newRequest(uri)
                .method(method)
                .timeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .headers(h -> {
                    headers.forEach(h::add);
                    applyUserAgent(h, headers);
                });
        request.send(listener);
        try {
            var response = listener.get(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            HttpFields httpHeaders = response.getHeaders();
            return new HttpResult<>(response.getStatus(), httpHeaders, listener.getInputStream(), uri);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Jetty request interrupted", ie);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Jetty request failed", e);
        }
    }

    private void applyUserAgent(HttpFields.Mutable fields, Map<String, String> headers) {
        if (config.userAgent() != null && !config.userAgent().isBlank() && !headers.containsKey("User-Agent")) {
            fields.add("User-Agent", config.userAgent());
        }
    }

    boolean shouldRetry(int status, int attempts, boolean idempotent) {
        if (attempts >= 1 + config.maxRetries()) {
            return false;
        }
        if (config.retryIdempotentOnly() && !idempotent) {
            throw new IllegalStateException("Retries are limited to idempotent requests by configuration");
        }
        return RETRY_STATUSES.contains(status);
    }

    boolean shouldRetryIOException(int attempts, boolean idempotent) {
        if (attempts >= 1 + config.maxRetries()) {
            return false;
        }
        if (config.retryIdempotentOnly() && !idempotent) {
            throw new IllegalStateException("Retries are limited to idempotent requests by configuration");
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
        if (startInclusive < 0) {
            throw new IllegalArgumentException("start must be >= 0");
        }
        if (endInclusive != null && endInclusive < startInclusive) {
            throw new IllegalArgumentException("end must be >= start");
        }
        return endInclusive == null
                ? "bytes=%d-".formatted(startInclusive)
                : "bytes=%d-%d".formatted(startInclusive, endInclusive);
    }

}

