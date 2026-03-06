package riid.app.service;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.core.config.TestRegistryConfig;
import riid.core.fs.TestPaths;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;

@Tag("filesystem")
class ImageLoadingFacadeFactoryTest {

    @Test
    void createsServiceFromConfig() throws Exception {
        String scheme = TestRegistryConfig.scheme();
        String host = TestRegistryConfig.host();
        int port = TestRegistryConfig.port();
        String yaml = """
                client:
                  http:
                    backoffExponentBase: 2
                  auth: {}
                  registries:
                    - scheme: %s
                      host: %s
                      port: %d
                dispatcher:
                  maxConcurrentRegistry: 2
                """.formatted(scheme, host, port);
        HostFilesystem fs = new NioHostFilesystem();
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
        fs.writeString(tmp, yaml);

        try (riid.app.service.ImageLoadingFacade svc = ImageLoadingFacade.createFromConfig(tmp)) {
        assertNotNull(svc);
        }
    }
}


