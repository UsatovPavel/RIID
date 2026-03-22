package riid.app.cli;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import riid.app.core.config.AppConfig;
import riid.app.core.config.ConfigResolvingLoaderProvider;
import riid.app.core.config.DaemonSettingsResolver;
import riid.app.core.error.AppException;
import riid.app.daemon.DaemonServer;
import riid.app.service.ImageLoadingFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.core.logging.MdcContext;
import riid.core.logging.MilestoneEventLogger;
import riid.runtime.RuntimeAdapter;

/**
 * Minimal CLI parser/runner for ImageLoadingFacade.
 */
public final class CliApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliApplication.class);
    private static final Path DEFAULT_CONFIG_PATH = CliParser.DEFAULT_CONFIG_PATH;

    enum ExitCode {
        OK(0),
        USAGE(64),
        RUNTIME_NOT_FOUND(65),
        FAILURE(1);

        private final int exitCode;

        ExitCode(int code) {
            this.exitCode = code;
        }

        int code() {
            return exitCode;
        }
    }

    private final ServiceFactory serviceFactory;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Set<String> availableRuntimes;
    private final DaemonRunner daemonRunner;

    public CliApplication(ServiceFactory serviceFactory,
                          Map<String, RuntimeAdapter> runtimes,
                          PrintWriter out,
                          PrintWriter err) {
        this(serviceFactory, runtimes, out, err, (options, loader, available) -> {
            AppConfig.DaemonConfig daemonConfig = DaemonSettingsResolver.resolve(options);
            DaemonServer server = new DaemonServer(
                    daemonConfig.bindHostOrDefault(),
                    daemonConfig.bindPortOrDefault(),
                    loader,
                    available,
                    daemonConfig.maxConcurrentPullsOrDefault(),
                    daemonConfig.requestTimeoutOrDefault(),
                    daemonConfig.overloadPolicyOrDefault()
            );
            server.startAndJoin();
        });
    }

    public CliApplication(ServiceFactory serviceFactory,
                          Map<String, RuntimeAdapter> runtimes,
                          PrintWriter out,
                          PrintWriter err,
                          DaemonRunner daemonRunner) {
        this.serviceFactory = Objects.requireNonNull(serviceFactory, "serviceFactory");
        this.availableRuntimes = Set.copyOf(Objects.requireNonNull(runtimes, "runtimes").keySet());
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.daemonRunner = Objects.requireNonNull(daemonRunner, "daemonRunner");
    }

    public static CliApplication createDefault() {
        return new CliApplication(
                ConfigResolvingLoaderProvider::create,
                ImageLoadingFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true)
        );
    }

    public static void main(String[] args) {
        CliApplication cli = createDefault();
        int code = cli.run(args);
        if (code != ExitCode.OK.code()) {
            System.exit(code);
        }
    }

    public int run(String[] args) {
        long requestStartedNs = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        MdcContext.putTraceId(traceId);
        MdcContext.putComponent("app");
        MdcContext.putOperation("request");
        MilestoneEventLogger.info(LOGGER)
                .addEvent("request.start")
                .addResult("success")
                .addDurationMs(0L)
                .log("Request started");
        try {
            CliParser.ParseResult result = CliParser.parse(args);
            if (result.errorMessage() != null) {
                err.println("Error: " + result.errorMessage());
                printUsage(err);
                MilestoneEventLogger.warn(LOGGER)
                        .addEvent("request.finish")
                        .addResult("error")
                        .addDurationFrom(requestStartedNs)
                        .addErrorKind("VALIDATION")
                        .addErrorCode("CLI_USAGE_ERROR")
                        .log("Request failed with usage error");
                return ExitCode.USAGE.code();
            }
            if (result.showHelp()) {
                printUsage(out);
                MilestoneEventLogger.info(LOGGER)
                        .addEvent("request.finish")
                        .addResult("success")
                        .addDurationFrom(requestStartedNs)
                        .log("Request finished (help)");
                return ExitCode.OK.code();
            }
            CliParser.CliOptions options = result.options();
            if (options.daemonMode()) {
                ImageLoader loader = serviceFactory.create(options);
                daemonRunner.run(options, loader, availableRuntimes);
                MilestoneEventLogger.info(LOGGER)
                        .addEvent("request.finish")
                        .addResult("success")
                        .addDurationFrom(requestStartedNs)
                        .log("Request finished (daemon)");
                return ExitCode.OK.code();
            }
            if (!availableRuntimes.contains(options.runtimeId())) {
                err.printf(
                        "Unknown runtime '%s'. Available: %s%n",
                        options.runtimeId(),
                        String.join(", ", availableRuntimes)
                );
                MilestoneEventLogger.warn(LOGGER)
                        .addEvent("request.finish")
                        .addResult("error")
                        .addDurationFrom(requestStartedNs)
                        .addErrorKind("VALIDATION")
                        .addErrorCode("RUNTIME_NOT_FOUND")
                        .log("Request failed: unknown runtime");
                return ExitCode.RUNTIME_NOT_FOUND.code();
            }
            ImageLoader loader = serviceFactory.create(options);
            loader.load(options.repository(), options.reference(), options.runtimeId());
            if (options.hasCerts()) {
                out.println("Note: cert/key/CA options accepted but not yet used (stub).");
            }
            MilestoneEventLogger.info(LOGGER)
                    .addEvent("request.finish")
                    .addResult("success")
                    .addDurationFrom(requestStartedNs)
                    .log("Request finished");
            return ExitCode.OK.code();
        } catch (Exception e) {
            err.println("Failed to load image: " + e.getMessage());
            String errorCode = e instanceof AppException appException
                    ? appException.errorCode()
                    : "REQUEST_EXECUTION_FAILED";
            MilestoneEventLogger.error(LOGGER)
                    .addCause(e)
                    .addEvent("request.finish")
                    .addResult("error")
                    .addDurationFrom(requestStartedNs)
                    .addErrorKind("INTERNAL")
                    .addErrorCode(errorCode)
                    .log("Request failed");
            return ExitCode.FAILURE.code();
        } finally {
            MdcContext.clearRequestContext();
        }
    }

    private void printUsage(PrintWriter writer) {
        String usage = String.join("%n",
                "Usage: riid --repo <name> [--tag <tag>|--digest <sha256:...>] --runtime <id>",
                "   or: riid --daemon",
                "       [--config <path>] [--username <user>",
                "        (--password <pwd>|--password-env <VAR>|--password-file <path>)]",
                "       [--cert-path <path>] [--key-path <path>] [--ca-path <path>] [--help]",
                "Flags:",
                "  --repo           Repository name (e.g., library/busybox)",
                "  --tag/--ref      Tag to pull (default: latest). Ignored if --digest is provided",
                "  --digest         Digest to pull (format: sha256:...)",
                "  --runtime        Runtime id (available: %s)".formatted(String.join(", ", availableRuntimes)),
                "  --daemon         Run in daemon mode (Jetty HTTP IPC server)",
                "  --config         Path to YAML config (default path: %s)".formatted(DEFAULT_CONFIG_PATH),
                "                   If omitted and default file is missing, built-in defaults are used",
                "  --username       Registry username for basic auth",
                "  --password       Registry password (mutually exclusive with",
                "                     --password-env/--password-file)",
                "  --password-env   Name of env var containing the registry password",
                "  --password-file  Path to file containing the registry password",
                "  --cert-path      Path to client certificate (validated to exist, not used yet)",
                "  --key-path       Path to client private key (validated to exist, not used yet)",
                "  --ca-path        Path to CA certificate (validated to exist, not used yet)",
                "  --help           Show this message"
        );
        writer.println(usage);
        writer.flush();
    }

    public record CliOptions(Path configPath,
                             boolean configProvidedByUser,
                             boolean daemonMode,
                             String repository,
                             String reference,
                             String runtimeId,
                             Credentials credentials,
                             Path certPath,
                             Path keyPath,
                             Path caPath) {
        boolean hasCerts() {
            return certPath != null || keyPath != null || caPath != null;
        }
    }

    record ParseResult(CliOptions options, boolean showHelp, String errorMessage) {
    }

    @FunctionalInterface
    public interface ServiceFactory {
        ImageLoader create(CliOptions options) throws Exception;
    }

    @FunctionalInterface
    public interface ImageLoader {
        String load(String repository, String reference, String runtimeId);
    }

    @FunctionalInterface
    public interface DaemonRunner {
        void run(CliParser.CliOptions options, ImageLoader loader, Set<String> availableRuntimes) throws Exception;
    }
}
