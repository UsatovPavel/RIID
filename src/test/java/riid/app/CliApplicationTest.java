package riid.app;

import org.junit.jupiter.api.Test;
import riid.runtime.PodmanRuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliApplicationTest {
    private static final String RUNTIME_PODMAN = "podman";
    private static final String REPO_BUSYBOX = "library/busybox";

    @Test
    void failsWithUsageWhenNoArgs() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication appWithErr = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8)),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = appWithErr.run(new String[]{});

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Usage"),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void failsWhenRepoMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--runtime", RUNTIME_PODMAN});

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Repository is required"));
    }

    @Test
    void failsWhenRuntimeMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--repo", REPO_BUSYBOX});

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Runtime id is required"));
    }

    @Test
    void failsOnUnknownRuntime() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> {
                    throw new AssertionError("Service factory must not be invoked on invalid runtime");
                },
                Map.of(RUNTIME_PODMAN, new PodmanRuntimeAdapter()),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--repo", REPO_BUSYBOX, "--runtime", "unknown"});

        assertEquals(CliApplication.EXIT_RUNTIME_NOT_FOUND, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Unknown runtime"));
    }

    @Test
    void passesArgsToServiceOnSuccess() {
        AtomicReference<Path> configSeen = new AtomicReference<>();
        AtomicReference<String> repoSeen = new AtomicReference<>();
        AtomicReference<String> refSeen = new AtomicReference<>();
        AtomicReference<String> runtimeSeen = new AtomicReference<>();

        CliApplication app = new CliApplication(
                options -> {
                    configSeen.set(options.configPath());
                    return (repo, ref, runtime) -> {
                        repoSeen.set(repo);
                        refSeen.set(ref);
                        runtimeSeen.set(runtime);
                        return "ok";
                    };
                },
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--config", "config.yaml",
                "--repo", REPO_BUSYBOX,
                "--tag", "latest",
                "--runtime", RUNTIME_PODMAN
        });

        assertEquals(CliApplication.EXIT_OK, code);
        assertEquals(Path.of("config.yaml"), configSeen.get());
        assertEquals(REPO_BUSYBOX, repoSeen.get());
        assertEquals("latest", refSeen.get());
        assertEquals(RUNTIME_PODMAN, runtimeSeen.get());
    }
}

