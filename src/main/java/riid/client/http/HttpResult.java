package riid.client.http;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.eclipse.jetty.http.HttpFields;

/**
 * Lightweight HTTP response DTO returned by HttpExecutor (Jetty-based).
 */
public record HttpResult<T>(int statusCode, HttpFields headers, T body, URI uri) {
    public enum HeaderName {
        LOCATION("Location"),
        CONTENT_RANGE("Content-Range"),
        CONTENT_TYPE("Content-Type"),
        CONTENT_LENGTH("Content-Length"),
        DOCKER_CONTENT_DIGEST("Docker-Content-Digest");

        private final String headerValue;

        HeaderName(String value) {
            this.headerValue = value;
        }

        public String value() {
            return headerValue;
        }
    }

    private Optional<String> firstHeader(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    public Optional<String> firstHeader(HeaderName name) {
        return firstHeader(name.value());
    }

    public List<String> allHeaders(String name) {
        return headers.getValuesList(name);
    }

    public List<String> allHeaders(HeaderName name) {
        return allHeaders(name.value());
    }

    private OptionalLong firstHeaderAsLong(String name) {
        Optional<String> opt = firstHeader(name);
        if (opt.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(opt.get()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public OptionalLong firstHeaderAsLong(HeaderName name) {
        return firstHeaderAsLong(name.value());
    }
}

