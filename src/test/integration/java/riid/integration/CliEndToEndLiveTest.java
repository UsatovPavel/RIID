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
import riid.p2p.P2PExecutor;
import riid.runtime.RuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

/**
 * Full-flow smoke: CLI args -> ConfigLoader -> ImageLoadingFacade -> dispatcher -> registry -> runtime (stub).
 * Live: hits Docker Hub for library/busybox:latest.
 */
@Tag("filesystem")
@Tag("e2e")
@Tag("live")
class CliEndToEndLiveTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void cliDownloadsAndInvokesRuntimeStub() throws Exception {
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
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to load image", e);
                        }
                    };
                },
                Map.of(runtime.runtimeId(), runtime),
                new PrintWriter(new OutputStreamWriter(outBuf, java.nio.charset.StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, java.nio.charset.StandardCharsets.UTF_8), true)
        );

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        int code;
        try {
            code = cli.run(new String[]{
                    "--config", config.toString(),
                    "--repo", "library/busybox",
                    "--tag", "latest",
                    "--runtime", runtime.runtimeId()
            });
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        if (code != 0) {
            fail("CLI exit code " + code + "\nSTDOUT:\n" + outBuf + "\nSTDERR:\n" + errBuf);
        }
        assertTrue(runtime.called.get(), "runtime should be invoked");
        assertTrue(runtime.archiveExisted.get(), "archive must exist during import");
        assertTrue(runtime.archiveSize > 0, "archive must be non-empty");
        assertTrue(runtime.lastArchive != null, "archive path should be recorded");

        List<JsonNode> events = parseStructuredEvents(appender.list);
        JsonNode sourceFetch = findEvent(events, "source.fetch", "success");
        JsonNode requestFinish = findEvent(events, "request.finish", "success");

        assertTrue(sourceFetch.path("milestone").asBoolean(false), "source.fetch should be milestone");
        assertTrue(requestFinish.path("milestone").asBoolean(false), "request.finish should be milestone");

        String traceId = sourceFetch.path("trace_id").asText();
        assertFalse(traceId == null || traceId.isBlank() || "none".equals(traceId),
                "trace_id should be present for request");
        assertEquals(traceId, requestFinish.path("trace_id").asText(), "trace_id should propagate to request.finish");
    }

    private static List<JsonNode> parseStructuredEvents(List<ILoggingEvent> rawLogs) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (ILoggingEvent event : rawLogs) {
            String message = event.getFormattedMessage();
            if (message == null || message.isBlank() || !message.startsWith("{")) {
                continue;
            }
            events.add(OBJECT_MAPPER.readTree(message));
        }
        return events;
    }

    private static JsonNode findEvent(List<JsonNode> events, String eventName, String result) {
        return events.stream()
                .filter(n -> eventName.equals(n.path("event").asText()))
                .filter(n -> result.equals(n.path("result").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing structured event: " + eventName + "/" + result));
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

