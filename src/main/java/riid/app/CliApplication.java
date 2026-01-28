package riid.app;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;

import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.config.ConfigLoader;
import riid.config.GlobalConfig;
import riid.runtime.RuntimeAdapter;

/**
 * Minimal CLI parser/runner for ImageLoadingFacade.
 */
public final class CliApplication {
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
                CliApplication::defaultServiceFactory,
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
        ParseResult result = CliParser.parse(args);
        if (result.errorMessage != null) {
            err.println("Error: " + result.errorMessage);
            printUsage(err);
            return ExitCode.USAGE.code();
        }
        if (result.showHelp) {
            printUsage(out);
            return ExitCode.OK.code();
        }
        CliOptions options = result.options;
        if (!availableRuntimes.contains(options.runtimeId())) {
            err.printf(
                    "Unknown runtime '%s'. Available: %s%n",
                    options.runtimeId(),
                    String.join(", ", availableRuntimes)
            );
            return ExitCode.RUNTIME_NOT_FOUND.code();
        }
        try {
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
            return ExitCode.OK.code();
        } catch (Exception e) {
            err.println("Failed to load image: " + e.getMessage());
            return ExitCode.FAILURE.code();
        }
    }

    private static ImageLoader defaultServiceFactory(CliOptions options) throws Exception {
        GlobalConfig config = ConfigLoader.load(options.configPath());
        RegistryEndpoint endpoint = config.client().registries().getFirst();
        if (options.credentials() != null) {
            endpoint = new RegistryEndpoint(
                    endpoint.scheme(),
                    endpoint.host(),
                    endpoint.port(),
                    options.credentials()
            );
        }
        String registry = endpoint.registryName();
        return (repository, reference, runtimeId) -> {
            try (ImageLoadingFacade facade = ImageLoadingFacade.createFromConfig(
                    options.configPath(),
                    options.credentials()
            )) {
                return facade.load(
                        ImageId.fromRegistry(registry, repository, reference),
                        runtimeId
                ).toString();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load image", e);
            }
        };
    }
    private void printUsage(PrintWriter writer) {
        Path defaultConfigPath = Paths.get("config.yaml");
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
                "  --config         Path to YAML config (default: %s)".formatted(defaultConfigPath),
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

    /**
     * Parses CLI arguments and performs basic validation.
     */
    static final class CliParser {
        private static final String ARG_PATH = "path";
        private static final int MAX_PASSWORD_SOURCES = 1;

        private CliParser() {
        }

        static ParseResult parse(String[] args) {
            if (args == null || args.length == 0) {
                return new ParseResult(null, false, "No arguments provided");
            }
            Options parsedOptions = new Options();
            parsedOptions.addOption(Option.builder("h")
                    .longOpt("help")
                    .desc("Show help")
                    .build());
            addOption(parsedOptions, "config", ARG_PATH);
            addOption(parsedOptions, "repo", "name");
            addOption(parsedOptions, "tag", "tag");
            addOption(parsedOptions, "ref", "ref");
            addOption(parsedOptions, "digest", "digest");
            addOption(parsedOptions, "runtime", "id");
            addOption(parsedOptions, "username", "user");
            addOption(parsedOptions, "password", "pwd");
            addOption(parsedOptions, "password-env", "var");
            addOption(parsedOptions, "password-file", ARG_PATH);
            addOption(parsedOptions, "cert-path", ARG_PATH);
            addOption(parsedOptions, "key-path", ARG_PATH);
            addOption(parsedOptions, "ca-path", ARG_PATH);

            CommandLine cmd;
            CommandLineParser parser = new DefaultParser();
            try {
                cmd = parser.parse(parsedOptions, args);
            } catch (UnrecognizedOptionException e) {
                return new ParseResult(null, false, "Unknown option: " + e.getOption());
            } catch (MissingArgumentException e) {
                return new ParseResult(null, false,
                        "Missing value for " + formatOption(e.getOption()));
            } catch (ParseException e) {
                return new ParseResult(null, false, e.getMessage());
            }

            if (!cmd.getArgList().isEmpty()) {
                return new ParseResult(null, false, "Unexpected argument: " + cmd.getArgList().getFirst());
            }
            if (cmd.hasOption("help")) {
                return new ParseResult(null, true, null);
            }

            Path configPath = Paths.get(cmd.getOptionValue("config", "config.yaml"));
            String repo = cmd.getOptionValue("repo");
            String tag = cmd.getOptionValue("tag");
            String ref = cmd.getOptionValue("ref");
            String digest = cmd.getOptionValue("digest");
            String runtimeId = cmd.getOptionValue("runtime");
            String username = cmd.getOptionValue("username");
            String password = cmd.getOptionValue("password");
            String passwordEnv = cmd.getOptionValue("password-env");
            Path passwordFile = cmd.hasOption("password-file")
                    ? Paths.get(cmd.getOptionValue("password-file"))
                    : null;
            Path certPath = cmd.hasOption("cert-path") ? Paths.get(cmd.getOptionValue("cert-path")) : null;
            Path keyPath = cmd.hasOption("key-path") ? Paths.get(cmd.getOptionValue("key-path")) : null;
            Path caPath = cmd.hasOption("ca-path") ? Paths.get(cmd.getOptionValue("ca-path")) : null;
            if (repo == null || repo.isBlank()) {
                return new ParseResult(null, false, "Repository is required (--repo)");
            }
            if (runtimeId == null || runtimeId.isBlank()) {
                return new ParseResult(null, false, "Runtime id is required (--runtime)");
            }

            if (countNonNull(password, passwordEnv, passwordFile) > MAX_PASSWORD_SOURCES) {
                return new ParseResult(null, false, "Use only one of --password, --password-env or --password-file");
            }
            String resolvedPassword = password;
            if (passwordEnv != null) {
                resolvedPassword = System.getenv(passwordEnv);
                if (resolvedPassword == null || resolvedPassword.isBlank()) {
                    return new ParseResult(null, false, "Env var " + passwordEnv + " is not set or empty");
                }
            } else if (passwordFile != null) {
                try {
                    resolvedPassword = Files.readString(passwordFile).trim();
                } catch (IOException e) {
                    return new ParseResult(null, false, "Unable to read password file: " + e.getMessage());
                }
                if (resolvedPassword.isBlank()) {
                    return new ParseResult(null, false, "Password file is empty: " + passwordFile);
                }
            }
            if (username != null && resolvedPassword == null) {
                return new ParseResult(null, false, "Password is required when username is provided");
            }
            if (resolvedPassword != null && username == null) {
                return new ParseResult(null, false, "Username is required when password is provided");
            }
            Credentials credentials = null;
            if (username != null) {
                credentials = Credentials.basic(username, resolvedPassword);
            }

            if (certPath != null && !Files.exists(certPath)) {
                return new ParseResult(null, false, "cert-path does not exist: " + certPath);
            }
            if (keyPath != null && !Files.exists(keyPath)) {
                return new ParseResult(null, false, "key-path does not exist: " + keyPath);
            }
            if (caPath != null && !Files.exists(caPath)) {
                return new ParseResult(null, false, "ca-path does not exist: " + caPath);
            }
            String reference = digest != null
                    ? digest
                    : (tag != null ? tag : (ref != null ? ref : "latest"));

            CliOptions cliOptions = new CliOptions(
                    configPath,
                    repo,
                    reference,
                    runtimeId,
                    credentials,
                    certPath,
                    keyPath,
                    caPath
            );
            return new ParseResult(cliOptions, false, null);
        }

        @SafeVarargs
        private static int countNonNull(Object... items) {
            int count = 0;
            for (Object item : items) {
                if (item != null) {
                    count++;
                }
            }
            return count;
        }

        private static void addOption(Options options, String longOpt, String argName) {
            options.addOption(Option.builder()
                    .longOpt(longOpt)
                    .hasArg()
                    .argName(argName)
                    .build());
        }

        private static String formatOption(Option option) {
            if (option == null) {
                return "option";
            }
            if (option.getLongOpt() != null) {
                return "--" + option.getLongOpt();
            }
            return "-" + option.getOpt();
        }
    }

    @FunctionalInterface
    public interface ServiceFactory {
        ImageLoader create(CliOptions options) throws Exception;
    }

    @FunctionalInterface
    public interface ImageLoader {
        String load(String repository, String reference, String runtimeId);
    }
}

