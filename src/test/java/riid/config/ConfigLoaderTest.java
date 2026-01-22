package riid.config;

import org.junit.jupiter.api.Test;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

    private static final String TMP_PREFIX = "config-";
    private static final String TMP_SUFFIX = ".yaml";

    @Test
    void loadsValidConfig() throws Exception {
        String yaml = """
                client:
                  http:
                    connectTimeout: PT5S
                    requestTimeout: PT10S
                    maxRetries: 2
                    retryIdempotentOnly: true
                    followRedirects: true
                    initialBackoff: PT0.2S
                    maxBackoff: PT2S
                  auth:
                    defaultTokenTtlSeconds: 600
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 3
                """;
        Path tmp = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
        Files.writeString(tmp, yaml);

        AppConfig cfg = ConfigLoader.load(tmp);
        assertEquals(1, cfg.client().registries().size());
        RegistryEndpoint ep = cfg.client().registries().get(0);
        assertEquals("https", ep.scheme());
        assertEquals("registry-1.docker.io", ep.host());
        assertEquals(3, cfg.dispatcher().maxConcurrentRegistry());
    }

    @Test
    void missingClientFailsValidation() throws Exception {
        String yaml = """
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void emptyRegistriesFails() throws Exception {
        String yaml = """
                client:
                  http: {}
                  auth: {}
                  registries: []
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void loadsAllHttpAndAuthFields() throws Exception {
        String yaml = """
                client:
                  http:
                    connectTimeout: PT3S
                    requestTimeout: PT7S
                    maxRetries: 5
                    initialBackoff: PT0.15S
                    maxBackoff: PT3S
                    retryIdempotentOnly: false
                    userAgent: "riid-test-agent"
                    followRedirects: false
                  auth:
                    defaultTokenTtlSeconds: 900
                  registries:
                    - scheme: https
                      host: example.org
                      port: 5000
                      credentials:
                        username: user1
                        password: pass1
                    - scheme: http
                      host: another.example
                      port: 80
                      credentials:
                        identityToken: token-123
                dispatcher:
                  maxConcurrentRegistry: 10
                """;
        Path tmp = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
        Files.writeString(tmp, yaml);

        AppConfig cfg = ConfigLoader.load(tmp);
        assertEquals(2, cfg.client().registries().size());
        var first = cfg.client().registries().get(0);
        var firstCreds = first.credentialsOpt();
        assertEquals("example.org", first.host());
        assertEquals(5000, first.port());
        assertEquals("user1", firstCreds.flatMap(Credentials::usernameOpt).orElse(null));
        assertEquals("pass1", firstCreds.flatMap(Credentials::passwordOpt).orElse(null));

        var second = cfg.client().registries().get(1);
        var secondCreds = second.credentialsOpt();
        assertEquals("http", second.scheme());
        assertEquals("another.example", second.host());
        assertEquals("token-123", secondCreds.flatMap(Credentials::identityTokenOpt).orElse(null));

        assertEquals(5, cfg.client().http().maxRetries());
        assertFalse(cfg.client().http().retryIdempotentOnly());
        assertEquals("riid-test-agent", cfg.client().http().userAgent());
        assertFalse(cfg.client().http().followRedirects());
        assertEquals(900, cfg.client().auth().defaultTokenTtlSeconds());
        assertEquals(10, cfg.dispatcher().maxConcurrentRegistry());
    }

    @Test
    void noDefaultHttpConfigWhenMissing() throws Exception {
        String yaml = """
                client:
                  auth: {}
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 2
                """;
        Path tmp = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
        Files.writeString(tmp, yaml);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void invalidHttpConnectTimeoutFailsValidation() throws Exception {
        String yaml = """
                client:
                  http:
                    connectTimeout: -PT5S
                  auth: {}
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile(TMP_PREFIX, TMP_SUFFIX);
        Files.writeString(tmp, yaml);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }
}

