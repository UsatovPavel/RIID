package riid.client.http;

import java.net.http.HttpClient;

/**
 * Factory for configured java.net.http.HttpClient.
 */
public final class HttpClientFactory {
    private HttpClientFactory() {
    }

    public static HttpClient create(HttpClientConfig config) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .followRedirects(config.followRedirects()
                        ? HttpClient.Redirect.NORMAL
                        : HttpClient.Redirect.NEVER)
                .build();
    }
}

