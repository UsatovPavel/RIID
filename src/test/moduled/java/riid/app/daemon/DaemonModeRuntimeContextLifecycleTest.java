package riid.app.daemon;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import riid.app.cli.CliApplication;
import riid.app.cli.CliParser;
import riid.app.core.model.ImageId;
import riid.app.service.ImageLoadingFacade;
import riid.app.service.LoadOutcome;
import riid.core.config.TestRegistryConfig;
import riid.runtime.adapter.RuntimeId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DaemonModeRuntimeContextLifecycleTest {
    private static final RuntimeId RUNTIME_PODMAN = RuntimeId.PODMAN;
    private static final String REPO_BUSYBOX = "library/busybox";

    private static LoadOutcome mockLoadOutcome(String repo, String ref) {
        return new LoadOutcome(ImageId.fromRegistry(TestRegistryConfig.registryName(), repo, ref), -1L);
    }

    @Test
    void daemonModeUsesDaemonRuntimeContextLifecycle() {
        AtomicInteger createCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger daemonRunnerCalls = new AtomicInteger();

        CliApplication.DaemonServiceFactory daemonFactory = new CliApplication.DaemonServiceFactory() {
            @Override
            public CliApplication.ImageLoader create(CliParser.CliOptions options,
                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
                throw new AssertionError("daemon path must use createDaemonRuntime");
            }

            @Override
            public DaemonRuntimeContext createDaemonRuntime(CliParser.CliOptions options,
                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
                createCalls.incrementAndGet();
                return new DaemonRuntimeContext((repo, ref, runtime) -> mockLoadOutcome(repo, ref),
                        () -> closeCalls.incrementAndGet());
            }
        };

        CliApplication app = new CliApplication(daemonFactory, ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                (options, loader, available, prometheusRegistry) -> {
                    daemonRunnerCalls.incrementAndGet();
                    loader.load(REPO_BUSYBOX, "latest", RUNTIME_PODMAN);
                });

        int code = app.run(new String[]{"--daemon"});

        assertEquals(0, code);
        assertEquals(1, createCalls.get());
        assertEquals(1, daemonRunnerCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void daemonModeReusesRuntimeContextForMultiplePulls() {
        AtomicInteger createCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger loadCalls = new AtomicInteger();

        CliApplication.DaemonServiceFactory daemonFactory = new CliApplication.DaemonServiceFactory() {
            @Override
            public CliApplication.ImageLoader create(CliParser.CliOptions options,
                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
                throw new AssertionError("daemon path must use createDaemonRuntime");
            }

            @Override
            public DaemonRuntimeContext createDaemonRuntime(CliParser.CliOptions options,
                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
                createCalls.incrementAndGet();
                return new DaemonRuntimeContext((repo, ref, runtime) -> {
                    loadCalls.incrementAndGet();
                    return mockLoadOutcome(repo, ref);
                }, () -> closeCalls.incrementAndGet());
            }
        };

        CliApplication app = new CliApplication(daemonFactory, ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                (options, loader, available, prometheusRegistry) -> {
                    loader.load(REPO_BUSYBOX, "latest", RUNTIME_PODMAN);
                    loader.load(REPO_BUSYBOX, "1.36", RUNTIME_PODMAN);
                    loader.load(REPO_BUSYBOX, "stable", RUNTIME_PODMAN);
                });

        int code = app.run(new String[]{"--daemon"});

        assertEquals(0, code);
        assertEquals(1, createCalls.get(), "runtime context must be created once per daemon run");
        assertEquals(3, loadCalls.get(), "loader should serve all pulls without graph rebuild");
        assertEquals(1, closeCalls.get(), "runtime context must be closed once on shutdown");
    }

    @Test
    void daemonModeClosesRuntimeContextWhenRunnerFails() {
        AtomicInteger createCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();

        CliApplication.DaemonServiceFactory daemonFactory = new CliApplication.DaemonServiceFactory() {
            @Override
            public CliApplication.ImageLoader create(CliParser.CliOptions options,
                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
                throw new AssertionError("daemon path must use createDaemonRuntime");
            }

            @Override
            public DaemonRuntimeContext createDaemonRuntime(CliParser.CliOptions options,
                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
                createCalls.incrementAndGet();
                return new DaemonRuntimeContext((repo, ref, runtime) -> mockLoadOutcome(repo, ref),
                        () -> closeCalls.incrementAndGet());
            }
        };

        CliApplication app = new CliApplication(daemonFactory, ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                (options, loader, available, prometheusRegistry) -> {
                    throw new RuntimeException("daemon runner failed");
                });

        int code = app.run(new String[]{"--daemon"});

        assertEquals(1, code);
        assertEquals(1, createCalls.get());
        assertEquals(1, closeCalls.get(), "runtime context must close even on daemon failure");
    }
}
