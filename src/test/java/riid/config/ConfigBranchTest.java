package riid.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import riid.client.core.config.AuthConfig;
import riid.client.core.config.ClientConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.dispatcher.DispatcherConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import sun.misc.Unsafe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.eclipse.jetty.http.UriCompliance.UNSAFE;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void throwsWhenDispatcherMissing() {
        AppConfig cfg = new AppConfig(validClient(), null);
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.MISSING_DISPATCHER.message(), ex.getMessage());
    }

    @Test
    void throwsWhenRegistriesNull() {
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), null);
        AppConfig cfg = new AppConfig(client, new DispatcherConfig(1));
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.NO_REGISTRIES.message(), ex.getMessage());
    }

    @Test
    void throwsWhenRegistryEntryNull() {
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(null);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), regs);
        AppConfig cfg = new AppConfig(client, new DispatcherConfig(1));
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.NULL_REGISTRY.message(), ex.getMessage());
    }

    @Test
    void throwsWhenSchemeMissing() {
        RegistryEndpoint ep = new RegistryEndpoint("", "example.org", -1, null);
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(ep);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), regs);
        AppConfig cfg = new AppConfig(client, new DispatcherConfig(1));
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        Assertions.assertEquals(ConfigValidationException.Reason.MISSING_SCHEME.message(), ex.getMessage());
    }

    @Test
    void throwsWhenHostMissing() {
        RegistryEndpoint ep = new RegistryEndpoint("https", "   ", -1, null);
        var regs = new java.util.ArrayList<RegistryEndpoint>();
        regs.add(ep);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), regs);
        AppConfig cfg = new AppConfig(client, new DispatcherConfig(1));
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
        AppConfig cfg = new AppConfig(client, new DispatcherConfig(1));
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        Assertions.assertEquals(ConfigValidationException.Reason.HTTP_CONNECT_TIMEOUT_POSITIVE.message(), ex.getMessage());
    }

    // ------------ internal branch coverage via reflection (ConfigValidator/checkDuration/validatePathIfPresent) ------------
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
        var exNull = assertThrows(InvocationTargetException.class, () -> m.invoke(null, null, "client.http.requestTimeout"));
        assertEquals(ConfigValidationException.Reason.HTTP_REQUEST_TIMEOUT_POSITIVE.message(), exNull.getCause().getMessage());
        // zero
        var exZero = assertThrows(InvocationTargetException.class, () -> m.invoke(null, Duration.ZERO, "client.http.initialBackoff"));
        assertEquals(ConfigValidationException.Reason.HTTP_INITIAL_BACKOFF_POSITIVE.message(), exZero.getCause().getMessage());
        // negative
        var exNeg = assertThrows(InvocationTargetException.class, () -> m.invoke(null, Duration.ofSeconds(-1), "client.http.maxBackoff"));
        assertEquals(ConfigValidationException.Reason.HTTP_MAX_BACKOFF_POSITIVE.message(), exNeg.getCause().getMessage());

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
        assertEquals(ConfigValidationException.Reason.AUTH_CERT_MISSING.message() + ": no-such-file.pem", exMissing.getCause().getMessage());

        var exKey = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "no-such-key.pem", "client.auth.keyPath"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exKey.getCause());
        assertEquals(ConfigValidationException.Reason.AUTH_KEY_MISSING.message() + ": no-such-key.pem", exKey.getCause().getMessage());

        var exCa = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "no-such-ca.pem", "client.auth.caPath"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exCa.getCause());
        assertEquals(ConfigValidationException.Reason.AUTH_CA_MISSING.message() + ": no-such-ca.pem", exCa.getCause().getMessage());

        // fallback branch (unknown field)
        var exUnknown = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, "missing.pem", "unknown.field"));
        Assertions.assertInstanceOf(ConfigValidationException.class, exUnknown.getCause());
        assertEquals("unknown.field must point to existing file: missing.pem", exUnknown.getCause().getMessage());
    }

    @Test
    void validateAuthTtlNonPositive() throws Exception {
        AuthConfig auth = unsafeAuth(0, null, null, null);
        var m = ConfigValidator.class.getDeclaredMethod("validateAuth", AuthConfig.class);
        m.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, auth));
        assertEquals(ConfigValidationException.Reason.AUTH_TTL_POSITIVE.message(), ex.getCause().getMessage());
    }

    @Test
    void throwsWhenCertPathMissing() {
        String missing = "no-such-file.pem";
        AuthConfig auth = new AuthConfig(300, missing, null, null);
        ClientConfig client = new ClientConfig(HttpClientConfig.builder().build(), auth, List.of(RegistryEndpoint.https("example.org")));
        AppConfig cfg = new AppConfig(client, new DispatcherConfig(1));
        var ex = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validate(cfg));
        assertEquals(ConfigValidationException.Reason.AUTH_CERT_MISSING.message() + ": " + missing, ex.getMessage());
    }

    private static ClientConfig validClient() {
        return new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), List.of(RegistryEndpoint.https("example.org")));
    }

    private static ClientConfig clientWithRegistries(RegistryEndpoint ep) {
        return new ClientConfig(HttpClientConfig.builder().build(), new AuthConfig(), List.of(ep));
    }

    @Test
    void validateHttpBackoffInverted() throws Exception {
        // обойти конструктор HttpClientConfig
        HttpClientConfig bad = unsafeHttp(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                true,
                "ua",
                true
        );

        var m = ConfigValidator.class.getDeclaredMethod("validateHttp", HttpClientConfig.class);
        m.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, bad));
        assertEquals(ConfigValidationException.Reason.HTTP_BACKOFF_INVERTED.message(), ex.getCause().getMessage());
    }

    @Test
    void validateHttpUserAgentBlankViaUnsafe() throws Exception {
        HttpClientConfig bad = unsafeHttp(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                true,
                "   ",
                true
        );

        var m = ConfigValidator.class.getDeclaredMethod("validateHttp", HttpClientConfig.class);
        m.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, bad));
        assertEquals(ConfigValidationException.Reason.HTTP_USER_AGENT_BLANK.message(), ex.getCause().getMessage());
    }

    @Test
    void validateHttpMaxRetriesNegativeViaUnsafe() throws Exception {
        HttpClientConfig bad = unsafeHttp(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                -5,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                true,
                "ua",
                true
        );

        var m = ConfigValidator.class.getDeclaredMethod("validateHttp", HttpClientConfig.class);
        m.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, bad));
        assertEquals(ConfigValidationException.Reason.HTTP_MAX_RETRIES_NEGATIVE.message(), ex.getCause().getMessage());
    }

    private static AuthConfig unsafeAuth(long ttl, String cert, String key, String ca) throws Exception {
        AuthConfig auth = (AuthConfig) UNSAFE.allocateInstance(AuthConfig.class);
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
        HttpClientConfig cfg = (HttpClientConfig) UNSAFE.allocateInstance(HttpClientConfig.class);
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> cls = target.getClass();
        Field f = null;
        while (cls != null) {
            try {
                f = cls.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignore) {
                cls = cls.getSuperclass();
            }
        }
        if (f == null) {
            throw new NoSuchFieldException(name);
        }
        try {
            long off = UNSAFE.objectFieldOffset(f);
            if (f.getType() == int.class) {
                UNSAFE.(target, off, (Integer) value);
            } else if (f.getType() == boolean.class) {
                UNSAFE.putBoolean(target, off, (Boolean) value);
            } else {
                UNSAFE.putObject(target, off, value);
            }
            return;
        } catch (Throwable ignored) {
            // fall back to reflection
        }

        f.setAccessible(true);
        try {
            Field mods = Field.class.getDeclaredField("modifiers");
            mods.setAccessible(true);
            mods.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        } catch (NoSuchFieldException ignored) {
            // JDKs without 'modifiers' field; best-effort
        }
        if (f.getType() == int.class) {
            f.setInt(target, (Integer) value);
        } else if (f.getType() == boolean.class) {
            f.setBoolean(target, (Boolean) value);
        } else {
            f.set(target, value);
        }
    }
}

