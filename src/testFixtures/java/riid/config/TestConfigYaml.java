package riid.core.config;

/**
 * Shared YAML snippets for integration tests.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestConfigYaml {
    private TestConfigYaml() {
    }

    public static String minimalDockerHubConfigWithEmptyAuth(int maxConcurrentRegistry) {
        return """
                client:
                  http:
                    backoffExponentBase: 2
                  auth: {}
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: %d
                """.formatted(maxConcurrentRegistry);
    }

    public static String dockerHubConfigWithRuntimeTempDir(int maxConcurrentRegistry, String tempDirectory) {
        return """
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
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: %d
                app:
                  tempDirectory: "%s"
                """.formatted(maxConcurrentRegistry, tempDirectory);
    }
}
