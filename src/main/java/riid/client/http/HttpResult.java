package riid.client.http;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.OptionalLong;

/**
 * Lightweight HTTP response DTO returned by HttpExecutor.
 */
public record HttpResult<T>(int statusCode, HttpHeaders headers, T body, URI uri) {
    public OptionalLong firstHeaderAsLong(String name) {
        var opt = headers.firstValue(name);
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

