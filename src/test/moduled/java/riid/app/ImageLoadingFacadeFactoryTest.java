package riid.app;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.fs.TestPaths;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

@Tag("filesystem")
class ImageLoadingFacadeFactoryTest {

    @Test
    void createsServiceFromConfig() throws Exception {
        String yaml = """
                client:
                  http: {}
                  auth: {}
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 2
                """;
        HostFilesystem fs = new NioHostFilesystem();
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
        fs.writeString(tmp, yaml);

        try (ImageLoadingFacade svc = ImageLoadingFacade.createFromConfig(tmp)) {
        assertNotNull(svc);
        }
    }
}


