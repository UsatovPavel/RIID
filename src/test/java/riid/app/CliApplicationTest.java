package riid.app;

import org.junit.jupiter.api.Test;
import riid.app.CliApplication;
import riid.app.ImageLoadServiceFactory;
import riid.runtime.PodmanRuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliApplicationTest {

    @Test
    void failsWithUsageWhenNoArgs() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication appWithErr = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new ByteArrayOutputStream()),
                new PrintWriter(errBuf, true)
        );

        int code = appWithErr.run(new String[]{});

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString().contains("Usage"), errBuf.toString());
    }

    @Test
    void failsWhenRepoMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new ByteArrayOutputStream(), true),
                new PrintWriter(errBuf, true)
        );

        int code = app.run(new String[]{"--runtime", "podman"});

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString().contains("Repository is required"));
    }

    @Test
    void failsWhenRuntimeMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new ByteArrayOutputStream(), true),
                new PrintWriter(errBuf, true)
        );

        int code = app.run(new String[]{"--repo", "library/busybox"});

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString().contains("Runtime id is required"));
    }

    @Test
    void failsOnUnknownRuntime() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> {
                    throw new AssertionError("Service factory must not be invoked on invalid runtime");
                },
                Map.of("podman", new PodmanRuntimeAdapter()),
                new PrintWriter(new ByteArrayOutputStream(), true),
                new PrintWriter(errBuf, true)
        );

        int code = app.run(new String[]{"--repo", "library/busybox", "--runtime", "unknown"});

        assertEquals(CliApplication.EXIT_RUNTIME_NOT_FOUND, code);
        assertTrue(errBuf.toString().contains("Unknown runtime"));
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
                new PrintWriter(new ByteArrayOutputStream(), true),
                new PrintWriter(new ByteArrayOutputStream(), true)
        );

        int code = app.run(new String[]{
                "--config", "config.yaml",
                "--repo", "library/busybox",
                "--tag", "latest",
                "--runtime", "podman"
        });

        assertEquals(CliApplication.EXIT_OK, code);
        assertEquals(Path.of("config.yaml"), configSeen.get());
        assertEquals("library/busybox", repoSeen.get());
        assertEquals("latest", refSeen.get());
        assertEquals("podman", runtimeSeen.get());
    }
}

