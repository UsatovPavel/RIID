package riid.core.config;

import java.util.Optional;

import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;

/**
 * Shared live-registry configuration for tests.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestRegistryConfig {
    private static final String PROP_SCHEME = "riid.test.registry.scheme";
    private static final String PROP_HOST = "riid.test.registry.host";
    private static final String PROP_PORT = "riid.test.registry.port";

    private static final String DEFAULT_SCHEME = "https";
    private static final String DEFAULT_HOST = "registry-1.docker.io";
    private static final int DEFAULT_PORT = -1;

    private TestRegistryConfig() {
    }

    public static String scheme() {
        return System.getProperty(PROP_SCHEME, DEFAULT_SCHEME);
    }

    public static String host() {
        return System.getProperty(PROP_HOST, DEFAULT_HOST);
    }

    public static int port() {
        String raw = System.getProperty(PROP_PORT);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        return Integer.parseInt(raw);
    }

    public static RegistryEndpoint endpoint() {
        return new RegistryEndpoint(scheme(), host(), port(), null);
    }

    /**
     * Same as {@link #endpoint()} but attaches {@link TestConfigYaml#dockerHubCredentialsFromEnv()} when set.
     */
    public static RegistryEndpoint endpointWithOptionalEnvCredentials() {
        Optional<Credentials> creds = TestConfigYaml.dockerHubCredentialsFromEnv();
        return creds.map(c -> new RegistryEndpoint(scheme(), host(), port(), c))
                .orElseGet(TestRegistryConfig::endpoint);
    }

    public static String registryName() {
        int p = port();
        return p > 0 ? host() + ":" + p : host();
    }

    public static String baseUrl() {
        int p = port();
        return p > 0 ? scheme() + "://" + host() + ":" + p : scheme() + "://" + host();
    }
}
