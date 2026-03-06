package riid.app.cli;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import riid.app.core.config.ConfigResolvingServiceProvider;
import riid.app.logging.AppLogErrorCode;
import riid.app.logging.AppStructuredEvents;
import riid.app.service.ImageLoadingFacade;
import riid.runtime.RuntimeAdapter;

/**
 * Minimal CLI parser/runner for ImageLoadingFacade.
 */
public final class CliApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliApplication.class);

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

    public CliApplication(ServiceFactory serviceFactory,
                          Map<String, RuntimeAdapter> runtimes,
                          PrintWriter out,
                          PrintWriter err) {
        this.serviceFactory = Objects.requireNonNull(serviceFactory, "serviceFactory");
        this.availableRuntimes = Set.copyOf(Objects.requireNonNull(runtimes, "runtimes").keySet());
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public static CliApplication createDefault() {
        return new CliApplication(
                ConfigResolvingServiceProvider::create,
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
        long requestStart = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        MDC.put("trace_id", traceId);
        AppStructuredEvents.requestStart(LOGGER, args == null ? 0 : args.length);

        int exitCode = ExitCode.FAILURE.code();
        String finishResult = "error";
        AppLogErrorCode finishErrorCode = AppLogErrorCode.REQUEST_FAILED;
        String finishErrorKind = "runtime";
        try {
            CliParser.ParseResult parseResult = CliParser.parse(args);
            if (parseResult.errorMessage() != null) {
                err.println("Error: " + parseResult.errorMessage());
                printUsage(err);
                exitCode = ExitCode.USAGE.code();
                finishResult = "error";
                finishErrorCode = AppLogErrorCode.USAGE_ERROR;
                finishErrorKind = "validation";
                return exitCode;
            }
            if (parseResult.showHelp()) {
                printUsage(out);
                exitCode = ExitCode.OK.code();
                finishResult = "success";
                finishErrorCode = null;
                finishErrorKind = null;
                return exitCode;
            }
            CliParser.CliOptions options = parseResult.options();
            if (!availableRuntimes.contains(options.runtimeId())) {
                err.printf(
                        "Unknown runtime '%s'. Available: %s%n",
                        options.runtimeId(),
                        String.join(", ", availableRuntimes)
                );
                exitCode = ExitCode.RUNTIME_NOT_FOUND.code();
                finishResult = "error";
                finishErrorCode = AppLogErrorCode.RUNTIME_NOT_FOUND;
                finishErrorKind = "validation";
                return exitCode;
            }
            ImageLoader loader = serviceFactory.create(options);
            loader.load(options.repository(), options.reference(), options.runtimeId());
            out.printf(
                    "Loaded %s (%s) into runtime %s%n",
                    options.repository(),
                    options.reference(),
                    options.runtimeId()
            );
            if (options.hasCerts()) {
                out.println("Note: cert/key/CA options accepted but not yet used (stub).");
            }
            exitCode = ExitCode.OK.code();
            finishResult = "success";
            finishErrorCode = null;
            finishErrorKind = null;
            return exitCode;
        } catch (Exception e) {
            err.println("Failed to load image: " + e.getMessage());
            exitCode = ExitCode.FAILURE.code();
            finishResult = "error";
            finishErrorCode = AppLogErrorCode.REQUEST_FAILED;
            finishErrorKind = e.getClass().getSimpleName();
            return exitCode;
        } finally {
            long totalMs = elapsedMs(requestStart);
            if ("success".equals(finishResult)) {
                AppStructuredEvents.requestFinishSuccess(LOGGER, totalMs, exitCode);
            } else {
                AppStructuredEvents.requestFinishError(
                        LOGGER, totalMs, exitCode, finishErrorCode, finishErrorKind);
            }
            MDC.remove("trace_id");
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
    
    private void printUsage(PrintWriter writer) {
        String usage = String.join("%n",
                "Usage: riid --repo <name> [--tag <tag>|--digest <sha256:...>] --runtime <id>",
                "       [--config <path>] [--username <user>",
                "        (--password <pwd>|--password-env <VAR>|--password-file <path>)]",
                "       [--cert-path <path>] [--key-path <path>] [--ca-path <path>] [--help]",
                "Flags:",
                "  --repo           Repository name (e.g., library/busybox)",
                "  --tag/--ref      Tag to pull (default: latest). Ignored if --digest is provided",
                "  --digest         Digest to pull (format: sha256:...)",
                "  --runtime        Runtime id (available: %s)".formatted(String.join(", ", availableRuntimes)),
                "  --config         Path to YAML config (default path: %s)".formatted(CliParser.DEFAULT_CONFIG_PATH),
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

    @FunctionalInterface
    public interface ServiceFactory {
        ImageLoader create(CliParser.CliOptions options) throws Exception;
    }

    @FunctionalInterface
    public interface ImageLoader {
        String load(String repository, String reference, String runtimeId);
    }
}

