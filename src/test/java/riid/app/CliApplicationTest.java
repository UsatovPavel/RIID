package riid.app;

import org.junit.jupiter.api.Test;
import riid.runtime.PodmanRuntimeAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
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

    @Test
    void showsHelpAndExitsOk() {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(outBuf, StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--help"});

        assertEquals(CliApplication.EXIT_OK, code);
        assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("Usage"));
    }

    @Test
    void digestOverridesTag() {
        AtomicReference<String> refSeen = new AtomicReference<>();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> {
                    refSeen.set(ref);
                    return "ok";
                },
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--tag", "latest",
                "--digest", "sha256:abc",
                "--runtime", RUNTIME_PODMAN
        });

        assertEquals(CliApplication.EXIT_OK, code);
        assertEquals("sha256:abc", refSeen.get());
    }

    @Test
    void rejectsMultiplePasswordSources() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "u",
                "--password", "p1",
                "--password-env", "SOME_ENV"
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Use only one of"));
    }

    @Test
    void requiresPasswordWhenUsernameProvided() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "user"
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Password is required"));
    }

    @Test
    void requiresUsernameWhenPasswordProvided() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--password", "secret"
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Username is required"));
    }

    @Test
    void failsWhenEnvPasswordMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        String missingVar = "NON_EXISTENT_" + UUID.randomUUID();
        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "user",
                "--password-env", missingVar
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("is not set or empty"));
    }

    @Test
    void failsWhenPasswordFileEmpty() throws Exception {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Path emptyFile = Files.createTempFile("pwd-", ".txt");
        Files.writeString(emptyFile, "");

        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "user",
                "--password-file", emptyFile.toString()
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Password file is empty"));
    }

    @Test
    void validatesCertPath() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Path missing = Path.of("does-not-exist.crt");

        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--cert-path", missing.toString()
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("cert-path does not exist"));
    }

    @Test
    void failsOnUnknownOption() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        CliApplication app = new CliApplication(
                options -> (repo, ref, runtime) -> "ignored",
                ImageLoadServiceFactory.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--unknown-flag"
        });

        assertEquals(CliApplication.EXIT_USAGE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Unknown option"));
    }
}

