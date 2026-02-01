package riid.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

import riid.app.fs.TestPaths;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

class ConfigBranchTest {

    private static final String YAML_SUFFIX = ".yaml";
    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    void missingFileFails() {
        Path missing = Path.of("no-such-config.yaml");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(missing));
    }

    @Test
    void invalidYamlFailsParsing() throws Exception {
        Path tmp = TestPaths.tempFile(fs, "bad-config", YAML_SUFFIX);
        fs.writeString(tmp, "not: [valid"); // broken YAML
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
        Path tmp = TestPaths.tempFile(fs, "cfg-null-http", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
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
        Path tmp = TestPaths.tempFile(fs, "cfg-null-auth", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
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
        Path tmp = TestPaths.tempFile(fs, "cfg-null-reg", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
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
        Path tmp = TestPaths.tempFile(fs, "cfg-bad-dispatcher", YAML_SUFFIX);
        fs.writeString(tmp, yaml);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(tmp));
    }
}

