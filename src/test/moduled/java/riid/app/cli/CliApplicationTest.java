package riid.app.cli;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.service.ImageLoadingFacade;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.runtime.PodmanRuntimeAdapter;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class CliApplicationTest {
    private static final String RUNTIME_PODMAN = "podman";
    private static final String REPO_BUSYBOX = "library/busybox";

    @Test
    void failsWithUsageWhenNoArgs() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication appWithErr = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8)),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = appWithErr.run(new String[]{});

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Usage"),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void failsWhenRepoMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--runtime", RUNTIME_PODMAN});

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Repository is required"));
    }

    @Test
    void failsWhenRuntimeMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--repo", REPO_BUSYBOX});

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Runtime id is required"));
    }

    @Test
    void failsOnUnknownRuntime() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> {
                    throw new AssertionError("Service factory must not be invoked on invalid runtime");
                },
                Map.of(RUNTIME_PODMAN, new PodmanRuntimeAdapter()),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--repo", REPO_BUSYBOX, "--runtime", "unknown"});

        assertEquals(riid.app.cli.CliApplication.ExitCode.RUNTIME_NOT_FOUND.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Unknown runtime"));
    }

    @Test
    void passesArgsToServiceOnSuccess() {
        AtomicReference<Path> configSeen = new AtomicReference<>();
        AtomicReference<String> repoSeen = new AtomicReference<>();
        AtomicReference<String> refSeen = new AtomicReference<>();
        AtomicReference<String> runtimeSeen = new AtomicReference<>();

        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> {
                    configSeen.set(options.configPath());
                    return (repo, ref, runtime) -> {
                        repoSeen.set(repo);
                        refSeen.set(ref);
                        runtimeSeen.set(runtime);
                        return "ok";
                    };
                },
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--config", "config/config.yaml",
                "--repo", REPO_BUSYBOX,
                "--tag", "latest",
                "--runtime", RUNTIME_PODMAN
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.OK.code(), code);
        assertEquals(Path.of("config/config.yaml"), configSeen.get());
        assertEquals(REPO_BUSYBOX, repoSeen.get());
        assertEquals("latest", refSeen.get());
        assertEquals(RUNTIME_PODMAN, runtimeSeen.get());
    }

    @Test
    void showsHelpAndExitsOk() {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(outBuf, StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{"--help"});

        assertEquals(riid.app.cli.CliApplication.ExitCode.OK.code(), code);
        assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("Usage"));
    }

    @Test
    void parserMarksConfigAsImplicitByDefault() {
        var result = CliParser.parse(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN
        });

        assertEquals(false, result.options().configProvidedByUser());
        assertEquals(Path.of("config", "config.yaml"), result.options().configPath());
    }

    @Test
    void parserMarksConfigAsExplicitWhenProvided() {
        var result = CliParser.parse(new String[]{
                "--config", "custom.yaml",
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN
        });

        assertEquals(true, result.options().configProvidedByUser());
        assertEquals(Path.of("custom.yaml"), result.options().configPath());
    }

    @Test
    void digestOverridesTag() {
        AtomicReference<String> refSeen = new AtomicReference<>();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> {
                    refSeen.set(ref);
                    return "ok";
                },
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--tag", "latest",
                "--digest", "sha256:abc",
                "--runtime", RUNTIME_PODMAN
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.OK.code(), code);
        assertEquals("sha256:abc", refSeen.get());
    }

    @Test
    void rejectsMultiplePasswordSources() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
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

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Use only one of"));
    }

    @Test
    void requiresPasswordWhenUsernameProvided() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "user"
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Password is required"));
    }

    @Test
    void requiresUsernameWhenPasswordProvided() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--password", "secret"
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Username is required"));
    }

    @Test
    void failsWhenEnvPasswordMissing() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
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

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("is not set or empty"));
    }

    @Tag("filesystem")
    @Test
    void failsWhenPasswordFileEmpty() throws Exception {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        HostFilesystem fs = new NioHostFilesystem();
        Path emptyFile = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "pwd-", ".txt");
        fs.writeString(emptyFile, "");

        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "user",
                "--password-file", emptyFile.toString()
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Password file is empty"));
    }

    @Tag("filesystem")
    @Test
    void acceptsPasswordFromFileWhenNotEmpty() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path passwordFile = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "pwd-ok-", ".txt");
        fs.writeString(passwordFile, "secret-from-file");

        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ok",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--username", "user",
                "--password-file", passwordFile.toString()
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.OK.code(), code);
    }

    @Test
    void validatesCertPath() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Path missing = Path.of("does-not-exist.crt");

        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                riid.app.service.ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--cert-path", missing.toString()
        });

        assertEquals(riid.app.cli.CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("cert-path does not exist"));
    }

    @Test
    void failsOnUnknownOption() {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        riid.app.cli.CliApplication app = new riid.app.cli.CliApplication(
                (options, meterRegistry) -> (repo, ref, runtime) -> "ignored",
                ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(errBuf, StandardCharsets.UTF_8), true)
        );

        int code = app.run(new String[]{
                "--repo", REPO_BUSYBOX,
                "--runtime", RUNTIME_PODMAN,
                "--unknown-flag"
        });

        assertEquals(CliApplication.ExitCode.USAGE.code(), code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("Unknown option"));
    }
}

