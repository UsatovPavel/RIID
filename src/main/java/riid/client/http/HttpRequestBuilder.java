package riid.client.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

/**
 * Utilities to build URIs for registry endpoints.
 */
public final class HttpRequestBuilder {
    private HttpRequestBuilder() {
    }

    public static URI buildUri(String scheme, String host, int port, String path) {
        try {
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            return new URI(scheme, null, host, port, normalizedPath, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI parts", e);
        }
    }

    public static URI buildUri(String scheme, String host, int port, String path, String query) {
        try {
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            return new URI(scheme, null, host, port, normalizedPath, query, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI parts", e);
        }
    }

    public static Map<String, String> withRange(Map<String, String> headers, String rangeValue) {
        Objects.requireNonNull(rangeValue, "range");
        headers.put("Range", rangeValue);
        return headers;
    }
}

