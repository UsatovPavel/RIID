package riid.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Thin wrapper over Jetty HttpClient with retries for idempotent GET/HEAD.
 */
public class HttpExecutor {
    private static final String METHOD_HEAD = HttpMethod.HEAD.asString();
    private static final List<Integer> RETRY_STATUSES = List.of(429, 502, 503, 504);
    private static final List<Integer> REDIRECT_STATUSES = List.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 5;

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
                HttpResult<InputStream> resp = executeWithRedirects(method, uri, headers);
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

    private HttpResult<InputStream> executeWithRedirects(String method,
                                                         URI uri,
                                                         Map<String, String> headers) throws IOException {
        URI current = uri;
        Map<String, String> currentHeaders = headers;
        int redirects = 0;
        while (true) {
            HttpResult<InputStream> resp = execute(method, current, currentHeaders);
            Optional<String> location = resp.firstHeader("Location");
            if (location.isEmpty() || !REDIRECT_STATUSES.contains(resp.statusCode())) {
                return resp;
            }
            closeQuietly(resp.body());
            if (redirects >= MAX_REDIRECTS) {
                return resp;
            }
            redirects++;
            URI next = current.resolve(location.get());
            if (!sameOrigin(current, next)) {
                currentHeaders = new java.util.LinkedHashMap<>(currentHeaders);
                currentHeaders.remove("Authorization");
            }
            current = next;
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
                        .followRedirects(config.followRedirects())
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
                throw new IOException("Jetty HEAD failed for " + uri, e);
            }
        }

        @SuppressWarnings("PMD.CloseResource")
        InputStreamResponseListener listener = new InputStreamResponseListener();
        Request request = client.newRequest(uri)
                .method(method)
                .timeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(config.followRedirects())
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
            throw new IOException("Jetty request failed for " + uri, e);
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

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // best effort for redirect responses
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.getScheme(), b.getScheme())
                && Objects.equals(a.getHost(), b.getHost())
                && a.getPort() == b.getPort();
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

