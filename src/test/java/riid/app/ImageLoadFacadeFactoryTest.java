package riid.app;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

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
        Path tmp = Files.createTempFile("config-", ".yaml");
        Files.writeString(tmp, yaml);

        try (ImageLoadFacade svc = ImageLoadFacade.createFromConfig(tmp)) {
            assertNotNull(svc);
        }
    }
}


