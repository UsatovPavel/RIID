package riid.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.client.core.config.AuthConfig;
import riid.client.core.config.ClientConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.DispatcherConfig;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import riid.app.fs.TestPaths;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidAccessibilityAlteration"})
class ConfigBranchTest {

    private static final String YAML_SUFFIX = ".yaml";
    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    void missingFileFails() {
        Path missing = Path.of("no-such-config.yaml");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(missing));
    }

    @Tag("filesystem")
    @Test
    void invalidYamlFailsParsing() throws Exception {
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "bad-config", YAML_SUFFIX);
        fs.writeString(tmp, "not: [valid"); // broken YAML
        assertThrows(RuntimeException.class, () -> ConfigLoader.load(tmp));
    }

    @Tag("filesystem")
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cfg-null-http", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
        //Client config must not have side-effect(get http from default HttpConfig)
    }

    @Tag("filesystem")
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cfg-null-auth", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Tag("filesystem")
    @Test
    void nullRegistriesFailsValidation() throws Exception {
        String yaml = """
                client:
                  http: {}
                  auth: { defaultTokenTtlSeconds: 5 }
                dispatcher:
                  maxConcurrentRegistry: 1
                """;
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cfg-null-reg", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Tag("filesystem")
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cfg-bad-dispatcher", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Tag("filesystem")
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cfg-bad-maxRetries", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }

    @Tag("filesystem")
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
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "cfg-minimal", YAML_SUFFIX);
        fs.writeString(tmp, yaml);

        GlobalConfig cfg = ConfigLoader.load(tmp);
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

    @Test
    void throwsWhenDispatcherMissing() {
        GlobalConfig cfg = new GlobalConfig(validClient(), null, null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.MISSING_DISPATCHER.message(), ex.getMessage());
    }

    @Test
    void throwsWhenRegistriesNull() {
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), null);
        GlobalConfig cfg = new GlobalConfig(client, new DispatcherConfig(1), null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.MISSING_REGISTRIES.message(), ex.getMessage());
    }

    @Test
    void throwsWhenRegistryEntryNull() {
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(null);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), regs);
        GlobalConfig cfg = new GlobalConfig(client, new DispatcherConfig(1), null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.NULL_REGISTRY.message(), ex.getMessage());
    }

    @Test
    void throwsWhenSchemeMissing() {
        RegistryEndpoint ep = new RegistryEndpoint("", "example.org", -1, null);
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(ep);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), regs);
        GlobalConfig cfg = new GlobalConfig(client, new DispatcherConfig(1), null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        Assertions.assertEquals(ConfigValidationException.Reason.MISSING_SCHEME.message(), ex.getMessage());
    }

    @Test
    void throwsWhenHostMissing() {
        RegistryEndpoint ep = new RegistryEndpoint("https", "   ", -1, null);
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(ep);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), regs);
        GlobalConfig cfg = new GlobalConfig(client, new DispatcherConfig(1), null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.MISSING_HOST.message(), ex.getMessage());
    }

    @Test
    void throwsWhenHttpMaxRetriesNegative() {
        assertThrows(IllegalArgumentException.class, () -> HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(-1)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(200))
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build());
    }

    @Test
    void throwsWhenBackoffInverted() {
        assertThrows(IllegalArgumentException.class, () -> HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(1)
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(1))
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build());
    }

    @Test
    void throwsWhenUserAgentBlank() {
        assertThrows(IllegalArgumentException.class, () -> HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(1)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(200))
                .retryIdempotentOnly(true)
                .userAgent("   ")
                .followRedirects(true)
                .build());
    }

    @Test
    void throwsWhenDurationInvalid() {
        HttpClientConfig http = HttpClientConfig.builder()
                .connectTimeout(Duration.ZERO)
                .requestTimeout(Duration.ofSeconds(1))
                .maxRetries(1)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(1))
                .retryIdempotentOnly(true)
                .userAgent("ua")
                .followRedirects(true)
                .build();
        ClientConfig client = new ClientConfig(http, new AuthConfig(), List.of(RegistryEndpoint.https("example.org")));
        GlobalConfig cfg = new GlobalConfig(client, new DispatcherConfig(1), null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        Assertions.assertEquals(
                ConfigValidationException.Reason.HTTP_CONNECT_TIMEOUT_POSITIVE.message(),
                ex.getMessage());
    }

    // ------------ internal branch coverage via reflection
    // (ConfigValidator/checkDuration/validatePathIfPresent)
    @Test
    void validateRegistriesNullBranch() throws Exception {
        var m = ConfigValidator.class.getDeclaredMethod("validateRegistries", List.class);
        m.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, (Object) null));
        Assertions.assertTrue(ex.getCause() instanceof ConfigValidationException);
        assertEquals(ConfigValidationException.Reason.MISSING_REGISTRIES.message(), ex.getCause().getMessage());
    }

    @Test
    void validateRegistryEntryNullBranch() throws Exception {
        var m = ConfigValidator.class.getDeclaredMethod("validateRegistries", List.class);
        m.setAccessible(true);
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(null);
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, regs));
        Assertions.assertTrue(ex.getCause() instanceof ConfigValidationException);
        assertEquals(ConfigValidationException.Reason.NULL_REGISTRY.message(), ex.getCause().getMessage());
    }

    @Test
    void validateSchemeBlankBranch() throws Exception {
        var m = ConfigValidator.class.getDeclaredMethod("validateRegistries", List.class);
        m.setAccessible(true);
        var regs = java.util.List.of(new RegistryEndpoint(" ", "host", -1, null));
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, regs));
        Assertions.assertTrue(ex.getCause() instanceof ConfigValidationException);
        assertEquals(ConfigValidationException.Reason.MISSING_SCHEME.message(), ex.getCause().getMessage());
    }

    @Test
    void validateHostBlankBranch() throws Exception {
        var m = ConfigValidator.class.getDeclaredMethod("validateRegistries", List.class);
        m.setAccessible(true);
        var regs = java.util.List.of(new RegistryEndpoint("https", " ", -1, null));
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, regs));
        Assertions.assertTrue(ex.getCause() instanceof ConfigValidationException);
        assertEquals(ConfigValidationException.Reason.MISSING_HOST.message(), ex.getCause().getMessage());
    }

    @Test
    void checkDurationNullZeroNegativeBranches() throws Exception {
        var m = ConfigValidator.class.getDeclaredMethod("checkDuration", Duration.class, String.class);
        m.setAccessible(true);
        // null
        var exNull = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, null, "client.http.requestTimeout"));
        assertEquals(
                ConfigValidationException.Reason.HTTP_REQUEST_TIMEOUT_POSITIVE.message(),
                exNull.getCause().getMessage());
        // zero
        var exZero = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, Duration.ZERO, "client.http.initialBackoff"));
        assertEquals(
                ConfigValidationException.Reason.HTTP_INITIAL_BACKOFF_POSITIVE.message(),
                exZero.getCause().getMessage());
        // negative
        var exNeg = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, Duration.ofSeconds(-1), "client.http.maxBackoff"));
        assertEquals(
                ConfigValidationException.Reason.HTTP_MAX_BACKOFF_POSITIVE.message(),
                exNeg.getCause().getMessage());

        // fallback branch (unknown field)
        var exUnknown = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, Duration.ZERO, "client.http.unknownField"));
        assertEquals("client.http.unknownField must be positive", exUnknown.getCause().getMessage());
    }

    @Test
    void validatePathIfPresentBranches() throws Exception {
        var m = ConfigValidator.class.getDeclaredMethod("validatePathIfPresent", String.class, String.class);
        m.setAccessible(true);
        // null -> no exception
        m.invoke(null, null, "client.auth.certPath");
        // blank -> no exception
        m.invoke(null, "   ", "client.auth.certPath");
        // missing file -> exception
        var exMissing = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "no-such-file.pem", "client.auth.certPath"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exMissing.getCause());
        assertEquals(
                ConfigValidationException.Reason.AUTH_CERT_MISSING.message() + ": no-such-file.pem",
                exMissing.getCause().getMessage());

        var exKey = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "no-such-key.pem", "client.auth.keyPath"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exKey.getCause());
        assertEquals(
                ConfigValidationException.Reason.AUTH_KEY_MISSING.message() + ": no-such-key.pem",
                exKey.getCause().getMessage());

        var exCa = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "no-such-ca.pem", "client.auth.caPath"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exCa.getCause());
        assertEquals(
                ConfigValidationException.Reason.AUTH_CA_MISSING.message() + ": no-such-ca.pem",
                exCa.getCause().getMessage());

        // fallback branch (unknown field)
        var exUnknown = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "missing.pem", "unknown.field"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exUnknown.getCause());
        assertEquals("unknown.field must point to existing file: missing.pem", exUnknown.getCause().getMessage());
    }

    @Test
    void throwsWhenCertPathMissing() {
        String missing = "no-such-file.pem";
        AuthConfig auth = new AuthConfig(300, missing, null, null);
        ClientConfig client = new ClientConfig(
                HttpClientConfig.builder().build(),
                auth,
                List.of(RegistryEndpoint.https("example.org")));
        GlobalConfig cfg = new GlobalConfig(client, new DispatcherConfig(1), null, null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(
                ConfigValidationException.Reason.AUTH_CERT_MISSING.message() + ": " + missing,
                ex.getMessage());
    }

    private static ClientConfig validClient() {
        return new ClientConfig(
                HttpClientConfig.builder().build(),
                new AuthConfig(),
                List.of(RegistryEndpoint.https("example.org")));
    }

    private static ClientConfig clientWithRegistries(RegistryEndpoint ep) {
        return new ClientConfig(
                HttpClientConfig.builder().build(),
                new AuthConfig(),
                List.of(ep));
    }

    private static AuthConfig unsafeAuth(long ttl, String cert, String key, String ca) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        AuthConfig auth = (AuthConfig) unsafe.allocateInstance(AuthConfig.class);
        setField(auth, "defaultTokenTtlSeconds", ttl);
        setField(auth, "certPath", cert);
        setField(auth, "keyPath", key);
        setField(auth, "caPath", ca);
        return auth;
    }

    private static HttpClientConfig unsafeHttp(Duration ct,
                                               Duration rt,
                                               int mr,
                                               Duration ib,
                                               Duration mb,
                                               boolean retryIdem,
                                               String ua,
                                               boolean follow) throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        HttpClientConfig cfg = (HttpClientConfig) unsafe.allocateInstance(HttpClientConfig.class);
        setField(cfg, "connectTimeout", ct);
        setField(cfg, "requestTimeout", rt);
        setField(cfg, "maxRetries", mr);
        setField(cfg, "initialBackoff", ib);
        setField(cfg, "maxBackoff", mb);
        setField(cfg, "retryIdempotentOnly", retryIdem);
        setField(cfg, "userAgent", ua);
        setField(cfg, "followRedirects", follow);
        return cfg;
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        return (sun.misc.Unsafe) uf.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (f.getType() == int.class) {
            f.setInt(target, (Integer) value);
        } else if (f.getType() == boolean.class) {
            f.setBoolean(target, (Boolean) value);
        } else {
            f.set(target, value);
        }
    }
}

