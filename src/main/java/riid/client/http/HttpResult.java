package riid.client.http;

import org.eclipse.jetty.http.HttpFields;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Lightweight HTTP response DTO returned by HttpExecutor (Jetty-based).
 */
public record HttpResult<T>(int statusCode, HttpFields headers, T body, URI uri) {
    public Optional<String> firstHeader(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    public List<String> allHeaders(String name) {
        return headers.getValuesList(name);
    }

    public OptionalLong firstHeaderAsLong(String name) {
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
}

