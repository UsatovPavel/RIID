package riid.client.unit;

import org.junit.jupiter.api.Test;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpHelpersTest {

    @Test
    void buildsUriWithQuery() {
        URI uri = HttpRequestBuilder.buildUri("https", "example.com", -1, "/v2/path", "n=10&last=abc");
        assertEquals("https://example.com/v2/path?n=10&last=abc", uri.toString());
    }

    @Test
    void rangeHeader() {
        assertEquals("bytes=0-9", HttpExecutor.rangeHeader(0, 9L));
        assertEquals("bytes=5-", HttpExecutor.rangeHeader(5, null));
    }
}

