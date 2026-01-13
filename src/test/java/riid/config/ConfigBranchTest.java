package riid.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigBranchTest {

    private static final String YAML_SUFFIX = ".yaml";

    @Test
    void missingFileFails() {
        Path missing = Path.of("no-such-config.yaml");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(missing));
    }

    @Test
    void invalidYamlFailsParsing() throws Exception {
        Path tmp = Files.createTempFile("bad-config", YAML_SUFFIX);
        Files.writeString(tmp, "not: [valid"); // broken YAML
        assertThrows(RuntimeException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void nullHttpFailsValidation() throws Exception {
        String yaml = """
                client:
                  auth: { defaultTokenTtlSeconds: 10 }
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile("cfg-null-http", YAML_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
        //Client config must not have side-effect(get http from default HttpConfig)
    }

    @Test
    void nullAuthFailsValidation() throws Exception {
        String yaml = """
                client:
                  http: {}
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile("cfg-null-auth", YAML_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void nullRegistriesFailsValidation() throws Exception {
        String yaml = """
                client:
                  http: {}
                  auth: { defaultTokenTtlSeconds: 5 }
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile("cfg-null-reg", YAML_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void dispatcherInvalidConcurrencyFailsValidation() throws Exception {
        String yaml = """
                client:
                  http: {}
                  auth: { defaultTokenTtlSeconds: 5 }
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 0
                """;
        Path tmp = Files.createTempFile("cfg-bad-dispatcher", YAML_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void httpMaxRetriesNegativeFailsValidation() throws Exception {
        String yaml = """
                client:
                  http:
                    maxRetries: -1
                  auth: { defaultTokenTtlSeconds: 5 }
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile("cfg-bad-maxRetries", YAML_SUFFIX);
        Files.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Test
    void smokePrintsDefaultsFromMinimalConfig() throws Exception {
        String yaml = """
                client:
                  http: {}
                  auth:
                    defaultTokenTtlSeconds: 300
                  registries:
                    - scheme: https
                      host: registry.example.com
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = Files.createTempFile("cfg-minimal", YAML_SUFFIX);
        Files.writeString(tmp, yaml);

        AppConfig cfg = ConfigLoader.load(tmp);
        System.out.println("client.http.connectTimeout=" + cfg.client().http().connectTimeout());
        System.out.println("client.http.requestTimeout=" + cfg.client().http().requestTimeout());
        System.out.println("client.http.maxRetries=" + cfg.client().http().maxRetries());
        System.out.println("client.http.initialBackoff=" + cfg.client().http().initialBackoff());
        System.out.println("client.http.maxBackoff=" + cfg.client().http().maxBackoff());
        System.out.println("client.http.retryIdempotentOnly=" + cfg.client().http().retryIdempotentOnly());
        System.out.println("client.http.userAgent=" + cfg.client().http().userAgent());
        System.out.println("client.http.followRedirects=" + cfg.client().http().followRedirects());
        System.out.println("client.auth.defaultTokenTtlSeconds=" + cfg.client().auth().defaultTokenTtlSeconds());
        System.out.println("client.auth.certPath=" + cfg.client().auth().certPath());
        System.out.println("client.auth.keyPath=" + cfg.client().auth().keyPath());
        System.out.println("client.auth.caPath=" + cfg.client().auth().caPath());
        System.out.println("client.registries.size=" + cfg.client().registries().size());
    }
}

