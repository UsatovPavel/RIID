package riid.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.app.cli.CliApplication;
import riid.app.core.model.ImageId;
import riid.app.service.ImageLoadingFacade;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.cache.oci.TempFileCacheAdapter;
import riid.client.core.config.RegistryEndpoint;
import riid.core.config.ConfigLoader;
import riid.core.config.TestConfigYaml;
import riid.logging.TestRootLoggerEvents;
import riid.p2p.P2PExecutor;
import riid.runtime.RuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.ReflectiveOperationException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        try (TestRootLoggerEvents logs = TestRootLoggerEvents.attach("cli-e2e")) {
            HostFilesystem fs = new NioHostFilesystem();
            Path config = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "config-", ".yaml");
            fs.writeString(config, TestConfigYaml.minimalDockerHubConfigWithEmptyAuth(2));

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
                            } catch (IOException e) {
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

            String traceId = assertMilestoneStepLogging(logs);
            assertNonMilestoneRiidTraceMatches(logs, traceId);
        }
    }

    private static String assertMilestoneStepLogging(TestRootLoggerEvents logs)
            throws ReflectiveOperationException {
        List<Object> stepEvents = logs.events().stream()
                .filter(event -> TestRootLoggerEvents.keyValue(event, "event") != null)
                .toList();

        Set<String> seenEvents = new HashSet<>();
        Set<String> traceIds = new HashSet<>();
        for (Object event : stepEvents) {
            String eventName = TestRootLoggerEvents.keyValue(event, "event");
            seenEvents.add(eventName);
            String result = TestRootLoggerEvents.keyValue(event, "result");
            assertNotNull(result, "result must be present for event=" + eventName);
            String duration = TestRootLoggerEvents.keyValue(event, "duration_ms");
            assertNotNull(duration, "duration_ms must be present for event=" + eventName);
            Map<String, String> mdc = TestRootLoggerEvents.mdcPropertyMap(event);
            String id = mdc.get("trace_id");
            assertNotNull(id, "trace_id must be present for event=" + eventName);
            traceIds.add(id);
        }

        assertTrue(seenEvents.contains("request.start"));
        assertTrue(seenEvents.contains("manifest.fetch"));
        assertTrue(seenEvents.contains("source.select"));
        assertTrue(seenEvents.contains("source.fetch"));
        assertTrue(seenEvents.contains("archive.build"));
        assertTrue(seenEvents.contains("engine.import"));
        assertTrue(seenEvents.contains("request.finish"));
        assertEquals(1, traceIds.size(), "all key step events must share one trace_id");
        return traceIds.iterator().next();
    }

    private static void assertNonMilestoneRiidTraceMatches(TestRootLoggerEvents logs, String expectedTraceId)
            throws ReflectiveOperationException {
        List<Object> nonMilestoneRiid = logs.events().stream()
                .filter(event -> TestRootLoggerEvents.keyValue(event, "event") == null)
                .filter(CliEndToEndLiveTest::isRiidLogger)
                .toList();
        assertFalse(nonMilestoneRiid.isEmpty(), "expected at least one non-milestone log from riid.*");
        for (Object event : nonMilestoneRiid) {
            Map<String, String> mdc = TestRootLoggerEvents.mdcPropertyMap(event);
            assertNotNull(mdc, "MDC map expected for non-milestone riid log");
            String traceId = mdc.get("trace_id");
            assertNotNull(traceId,
                    "trace_id must be present on non-milestone riid logs (logger="
                            + loggerNameSafe(event) + ")");
            assertEquals(expectedTraceId, traceId,
                    "non-milestone log must use same trace_id as milestones (logger="
                            + loggerNameSafe(event) + ")");
        }
    }

    private static boolean isRiidLogger(Object event) {
        try {
            String name = TestRootLoggerEvents.loggerName(event);
            return name != null && name.startsWith("riid.");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String loggerNameSafe(Object event) {
        try {
            return TestRootLoggerEvents.loggerName(event);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
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
            } catch (IOException ignored) {
                this.archiveSize = 0L;
            }
        }
    }
}
