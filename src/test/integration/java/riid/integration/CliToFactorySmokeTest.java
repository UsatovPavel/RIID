package riid.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.app.CliApplication;
import riid.app.ImageLoadingFacade;
import riid.app.fs.NioHostFilesystem;
import riid.runtime.RuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke: CLI arg parsing -> ConfigLoader -> ImageLoadingFacade (no network).
 */
@Tag("filesystem")
@Tag("e2e")
class CliToFactorySmokeTest {

    @Test
    void reachesFactoryFromCli() throws Exception {
        var fs = new NioHostFilesystem();
        Path config = riid.app.fs.TestPaths.tempFile(fs, riid.app.fs.TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
        fs.writeString(config, """
                client:
                  http: {}
                  auth: {}
                  registries:
                    - scheme: https
                      host: registry-1.docker.io
                      port: -1
                dispatcher:
                  maxConcurrentRegistry: 2
                """);

        AtomicBoolean factoryCalled = new AtomicBoolean(false);

        CliApplication cli = new CliApplication(
                opts -> {
                    factoryCalled.set(true);
                    try (ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(opts.configPath())) {
                        Objects.requireNonNull(facade);
                    }
                    return (repo, ref, runtime) -> "ok";
                },
                Map.of("stub", new NoopRuntimeAdapter()),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = cli.run(new String[]{
                "--config", config.toString(),
                "--repo", "library/busybox",
                "--tag", "latest",
                "--runtime", "stub"
        });

        assertEquals(0, code);
        assertTrue(factoryCalled.get(), "Factory should be invoked from CLI flow");
    }

    private static final class NoopRuntimeAdapter implements RuntimeAdapter {
        @Override
        public String runtimeId() {
            return "stub";
        }

        @Override
        public void importImage(Path archive) {
            // no-op for smoke
        }
    }
}

