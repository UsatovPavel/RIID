package riid.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.app.CliApplication;
import riid.app.ImageId;
import riid.app.ImageLoadingFacade;
import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.config.ConfigLoader;
import riid.p2p.P2PExecutor;
import riid.runtime.RuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Full-flow smoke: CLI args -> ConfigLoader -> ImageLoadingFacade -> dispatcher -> registry -> runtime (stub).
 * Live: hits Docker Hub for library/busybox:latest.
 */
@Tag("filesystem")
@Tag("e2e")
@Tag("live")
class CliEndToEndLiveTest {

    @Test
    void cliDownloadsAndInvokesRuntimeStub() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path config = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
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

        RecordingRuntimeAdapter runtime = new RecordingRuntimeAdapter(fs);
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        CliApplication cli = new CliApplication(
                opts -> {
                    var cfg = ConfigLoader.load(opts.configPath());
                    RegistryEndpoint endpoint = cfg.client().registries().getFirst();
                    return (repo, ref, runtimeId) -> {
                        try (TempFileCacheAdapter cache = new TempFileCacheAdapter(fs);
                             ImageLoadingFacade svc = ImageLoadingFacade.createDefault(
                            endpoint,
                                     cache,
                            new P2PExecutor.NoOp(),
                            Map.of(runtime.runtimeId(), runtime),
                                     fs
                             )) {
                            String registry = endpoint.registryName();
                            return svc.load(
                            ImageId.fromRegistry(registry, repo, ref),
                            runtimeId
                            ).toString();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to load image", e);
                        }
                    };
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
        private final HostFilesystem fs;
        private final AtomicBoolean called = new AtomicBoolean(false);
        private final AtomicBoolean archiveExisted = new AtomicBoolean(false);
        private Path lastArchive;
        private long archiveSize;

        private RecordingRuntimeAdapter(HostFilesystem fs) {
            this.fs = fs;
        }

        @Override
        public String runtimeId() {
            return "stub";
        }

        @Override
        public void importImage(Path archive) {
            this.called.set(true);
            this.lastArchive = archive;
            this.archiveExisted.set(fs.exists(archive));
            try {
                this.archiveSize = fs.size(archive);
            } catch (Exception ignored) {
                this.archiveSize = 0L;
            }
        }
    }
}

