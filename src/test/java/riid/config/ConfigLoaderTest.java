package riid.config;

import org.junit.jupiter.api.Test;
import riid.client.core.config.RegistryEndpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

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
        Path tmp = Files.createTempFile("config-", ".yaml");
        Files.writeString(tmp, yaml);

        AppConfig cfg = ConfigLoader.load(tmp);
        assertEquals(1, cfg.client().registries().size());
        RegistryEndpoint ep = cfg.client().registries().getFirst();
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
        Path tmp = Files.createTempFile("config-", ".yaml");
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
        Path tmp = Files.createTempFile("config-", ".yaml");
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
        Path tmp = Files.createTempFile("config-", ".yaml");
        Files.writeString(tmp, yaml);

        AppConfig cfg = ConfigLoader.load(tmp);
        assertEquals(2, cfg.client().registries().size());
        assertEquals("example.org", cfg.client().registries().get(0).host());
        assertEquals(5000, cfg.client().registries().get(0).port());
        assertEquals("user1", cfg.client().registries().get(0).credentialsOpt().flatMap(c -> c.usernameOpt()).orElse(null));
        assertEquals("pass1", cfg.client().registries().get(0).credentialsOpt().flatMap(c -> c.passwordOpt()).orElse(null));
        assertEquals("http", cfg.client().registries().get(1).scheme());
        assertEquals("another.example", cfg.client().registries().get(1).host());
        assertEquals("token-123", cfg.client().registries().get(1).credentialsOpt().flatMap(c -> c.identityTokenOpt()).orElse(null));

        assertEquals(5, cfg.client().http().maxRetries());
        assertEquals(false, cfg.client().http().retryIdempotentOnly());
        assertEquals("riid-test-agent", cfg.client().http().userAgent());
        assertEquals(false, cfg.client().http().followRedirects());
        assertEquals(900, cfg.client().auth().defaultTokenTtlSeconds());
        assertEquals(10, cfg.dispatcher().maxConcurrentRegistry());
    }
}


