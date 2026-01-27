package riid.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

class ImageLoadFacadeFactoryTest {

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
        HostFilesystem fs = new NioHostFilesystem(null);
        Path tmp = fs.createTempFile("config-", ".yaml");
        fs.writeString(tmp, yaml);

        ImageLoadFacade svc = ImageLoadFacade.createFromConfig(tmp);
        assertNotNull(svc);
    }
}


