package riid.client.http;

import org.eclipse.jetty.client.HttpClient;

/**
 * Factory for configured Jetty HttpClient.
 */
public final class HttpClientFactory {
    private HttpClientFactory() {
    }

    public static HttpClient create(HttpClientConfig config) {
        try {
            HttpClient client = new HttpClient();
            client.setConnectTimeout(config.connectTimeout().toMillis());
            client.setFollowRedirects(config.followRedirects());
            client.setMaxRedirects(config.maxRedirects());
            client.start();
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Jetty HttpClient", e);
        }
    }
}

