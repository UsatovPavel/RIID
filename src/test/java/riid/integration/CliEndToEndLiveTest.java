package riid.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.app.CliApplication;
import riid.app.ImageId;
import riid.app.ImageLoadFacade;
import riid.app.fs.HostFilesystemTestSupport;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.config.ConfigLoader;
import riid.p2p.P2PExecutor;
import riid.runtime.RuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Full-flow smoke: CLI args -> ConfigLoader -> ImageLoadFacade -> dispatcher -> registry -> runtime (stub).
 * Live: hits Docker Hub for library/busybox:latest.
 */
@Tag("e2e")
@Tag("live")
class CliEndToEndLiveTest {

    @Test
    void cliDownloadsAndInvokesRuntimeStub() throws Exception {
        Path config = Files.createTempFile("config-", ".yaml");
        Files.writeString(config, """
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

        RecordingRuntimeAdapter runtime = new RecordingRuntimeAdapter();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        CliApplication cli = new CliApplication(
                opts -> {
                    var cfg = ConfigLoader.load(opts.configPath());
                    RegistryEndpoint endpoint = cfg.client().registries().getFirst();
                    var svc = ImageLoadFacade.createDefault(
                            endpoint,
                            new TempFileCacheAdapter(),
                            new P2PExecutor.NoOp(),
                            Map.of(runtime.runtimeId(), runtime),
                            HostFilesystemTestSupport.create()
                    );
                    String registry = ImageId.registryFor(endpoint);
                    return (repo, ref, runtimeId) -> svc.load(
                            ImageId.fromRegistry(registry, repo, ref),
                            runtimeId
                    );
                },
                Map.of(runtime.runtimeId(), runtime),
                new PrintWriter(new OutputStreamWriter(outBuf, java.nio.charset.StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, java.nio.charset.StandardCharsets.UTF_8), true)
        );

        int code = cli.run(new String[]{
                "--config", config.toString(),
                "--repo", "library/busybox",
                "--tag", "latest",
                "--runtime", runtime.runtimeId()
        });

        if (code != 0) {
            fail("CLI exit code " + code + "\nSTDOUT:\n" + outBuf + "\nSTDERR:\n" + errBuf);
        }
        assertTrue(runtime.called.get(), "runtime should be invoked");
        assertTrue(runtime.archiveExisted.get(), "archive must exist during import");
        assertTrue(runtime.archiveSize > 0, "archive must be non-empty");
        assertTrue(runtime.lastArchive != null, "archive path should be recorded");
    }

    private static final class RecordingRuntimeAdapter implements RuntimeAdapter {
        private final AtomicBoolean called = new AtomicBoolean(false);
        private final AtomicBoolean archiveExisted = new AtomicBoolean(false);
        private Path lastArchive;
        private long archiveSize;

        @Override
        public String runtimeId() {
            return "stub";
        }

        @Override
        public void importImage(Path archive) {
            this.called.set(true);
            this.lastArchive = archive;
            this.archiveExisted.set(Files.exists(archive));
            try {
                this.archiveSize = Files.size(archive);
            } catch (Exception ignored) {
                this.archiveSize = 0L;
            }
        }
    }
}

