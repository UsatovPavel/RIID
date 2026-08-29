package riid.integration.config_client;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.core.config.GlobalConfig;
import riid.core.config.ConfigLoader;
import riid.core.config.ConfigValidationException;
import riid.core.config.TestRegistryConfig;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;

@Tag("filesystem")
class ConfigLoaderTest {

    private static final String TMP_PREFIX = "config-";
    private static final String TMP_SUFFIX = ".yaml";
    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    void loadsValidConfig() throws Exception {
        String scheme = TestRegistryConfig.scheme();
        String host = TestRegistryConfig.host();
        int port = TestRegistryConfig.port();
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
                    backoffExponentBase: 2
                  auth:
                    defaultTokenTtlSeconds: 600
                  registries:
                    - scheme: %s
                      host: %s
                      port: %d
                dispatcher:
                  maxConcurrentRegistry: 3
                """.replace("\n", "%n").formatted(scheme, host, port);
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml);

        GlobalConfig cfg = ConfigLoader.load(tmp);
        assertEquals(1, cfg.client().registries().size());
        RegistryEndpoint ep = cfg.client().registries().get(0);
        assertEquals(scheme, ep.scheme());
        assertEquals(host, ep.host());
        assertEquals(port, ep.port());
        assertEquals(3, cfg.dispatcher().maxConcurrentRegistry());
    }

    @Test
    void missingClientFailsValidation() throws Exception {
        String yaml = """
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
        assertEquals(ConfigValidationException.class, ex.getClass());
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
        Path tmp = TestPaths.tempFile(fs, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
        assertEquals(ConfigValidationException.class, ex.getClass());
    }

    @Test
    void loadsAllHttpAndAuthFields() throws Exception {
        Path cert = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cert-", ".pem");
        Path key = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "key-", ".pem");
        Path ca = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "ca-", ".pem");

        String yaml = """
                client:
                  http:
                    connectTimeout: PT3S
                    requestTimeout: PT7S
                    maxRetries: 5
                    initialBackoff: PT0.15S
                    maxBackoff: PT3S
                    backoffExponentBase: 2
                    retryIdempotentOnly: false
                    userAgent: "riid-test-agent"
                    followRedirects: false
                  auth:
                    defaultTokenTtlSeconds: 900
                    certPath: %s
                    keyPath: %s
                    caPath: %s
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
                """.replace("\n", "%n");
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml.formatted(cert.toString(), key.toString(), ca.toString()));

        GlobalConfig cfg = ConfigLoader.load(tmp);
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
        assertEquals(cert.toString(), cfg.client().auth().certPath());
        assertEquals(key.toString(), cfg.client().auth().keyPath());
        assertEquals(ca.toString(), cfg.client().auth().caPath());
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml);

        var ex = assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
        assertEquals(ConfigValidationException.class, ex.getClass());
    }

    @Test
    void loadsRepositoryConfigYaml() {
        GlobalConfig cfg = ConfigLoader.load(Path.of("config", "config.yaml"));
        assertEquals(Duration.ofMinutes(30), cfg.client().http().requestTimeout());
        assertEquals(Duration.ofMinutes(2), cfg.client().http().imageTimeoutMin());
        assertEquals(Duration.ofMinutes(30), cfg.client().http().imageTimeoutMax());
        assertEquals(Duration.ofMinutes(30), cfg.app().daemonOrDefault().requestTimeoutOrDefault());
        assertEquals(90, cfg.app().daemonOrDefault().cacheHighWatermarkPercentOrDefault());
        assertEquals(50, cfg.app().daemonOrDefault().cacheLowWatermarkPercentOrDefault());
    }

    @Test
    void loadsOptionalCacheWatermarks() throws Exception {
        String yaml = """
                client:
                  http:
                    backoffExponentBase: 2
                  auth: {}
                  registries:
                    - scheme: https
                      host: example.org
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 1
                app:
                  daemon:
                    cacheHighWatermarkPercent: 80
                    cacheLowWatermarkPercent: 40
                """;
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml);

        GlobalConfig cfg = ConfigLoader.load(tmp);

        assertEquals(80, cfg.app().daemonOrDefault().cacheHighWatermarkPercentOrDefault());
        assertEquals(40, cfg.app().daemonOrDefault().cacheLowWatermarkPercentOrDefault());
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, TMP_PREFIX, TMP_SUFFIX);
        fs.writeString(tmp, yaml);

        var ex = assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
        assertEquals(ConfigValidationException.class, ex.getClass());
    }
}
