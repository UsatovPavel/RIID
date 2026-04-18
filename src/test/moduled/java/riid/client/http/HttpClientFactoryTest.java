package riid.client.http;

import java.nio.file.Path;
import java.time.Duration;

import org.eclipse.jetty.client.HttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import riid.client.core.config.AuthConfig;
import riid.core.config.TestConfigYaml;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;

@SuppressWarnings("PMD.CloseResource")
class HttpClientFactoryTest {

    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    void createsClientWithCustomCa() throws Exception {
        Path caPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "ca-", ".pem");
        fs.writeString(caPath, TestConfigYaml.CERT_PEM);
        AuthConfig auth = new AuthConfig(300, null, null, caPath.toString());

        HttpClientConfig cfg = defaultConfig();
        HttpClient client = HttpClientFactory.create(cfg, auth);
        try {
            assertTrue(client.isStarted());
            assertEquals(cfg.requestTimeout().toMillis(), client.getIdleTimeout());
        } finally {
            client.stop();
        }
    }

    @Test
    void createsClientWithMutualTlsCertAndKey() throws Exception {
        Path certPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cert-", ".pem");
        Path keyPath = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "key-", ".pem");
        fs.writeString(certPath, TestConfigYaml.CERT_PEM);
        fs.writeString(keyPath, TestConfigYaml.KEY_PEM);
        AuthConfig auth = new AuthConfig(300, certPath.toString(), keyPath.toString(), null);

        HttpClient client = HttpClientFactory.create(defaultConfig(), auth);
        try {
            assertTrue(client.isStarted());
        } finally {
            client.stop();
        }
    }

    @Test
    void failsFastWhenMtlsPairIncomplete() {
        AuthConfig auth = new AuthConfig(300, "cert.pem", null, null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> HttpClientFactory.create(defaultConfig(), auth));
        assertTrue(ex.getMessage().contains("SECURITY:TLS:HTTP_CLIENT_INIT_FAILED"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("SECURITY:TLS:CERT_KEY_PAIR_INVALID"));
    }

    private static HttpClientConfig defaultConfig() {
        return HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(1)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(200))
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build();
    }
}
