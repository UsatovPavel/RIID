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

import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.config.AppConfig;
import riid.config.ConfigLoader;
import riid.runtime.RuntimeAdapter;

/**
 * Minimal CLI parser/runner for ImageLoadFacade.
 */
public final class CliApplication {
    // package-visible for tests
    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 64;
    static final int EXIT_RUNTIME_NOT_FOUND = 65;
    static final int EXIT_FAILURE = 1;

    private static final Path DEFAULT_CONFIG_PATH = Paths.get("config", "config.yaml");
    private static final int MAX_PASSWORD_SOURCES = 1;

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
                options -> {
                    AppConfig config = ConfigLoader.load(options.configPath());
                    RegistryEndpoint endpoint = config.client().registries().getFirst();
                    if (options.credentials() != null) {
                        endpoint = new RegistryEndpoint(
                                endpoint.scheme(),
                                endpoint.host(),
                                endpoint.port(),
                                options.credentials()
                        );
                    }
                    String registry = ImageId.registryFor(endpoint);
                    return (repository, reference, runtimeId) -> {
                        try (ImageLoadFacade facade = ImageLoadFacade.createFromConfig(
                                options.configPath(),
                                options.credentials()
                        )) {
                            return facade.load(
                                    ImageId.fromRegistry(registry, repository, reference),
                                    runtimeId
                            );
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to load image", e);
                        }
                    };
                },
                ImageLoadFacade.defaultRuntimes(),
                new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true)
        );
    }

    public int run(String[] args) {
        ParseResult result = CliParser.parse(args);
        if (result.errorMessage != null) {
            err.println("Error: " + result.errorMessage);
            printUsage(err);
            return EXIT_USAGE;
        }
        if (result.showHelp) {
            printUsage(out);
            return EXIT_OK;
        }
        CliOptions options = result.options;
        if (!availableRuntimes.contains(options.runtimeId())) {
            err.printf(
                    "Unknown runtime '%s'. Available: %s%n",
                    options.runtimeId(),
                    String.join(", ", availableRuntimes)
            );
            return EXIT_RUNTIME_NOT_FOUND;
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
            return EXIT_OK;
        } catch (Exception e) {
            err.println("Failed to load image: " + e.getMessage());
            return EXIT_FAILURE;
        }
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
                "  --config         Path to YAML config (default: %s)".formatted(DEFAULT_CONFIG_PATH),
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
        private CliParser() {
        }

        static ParseResult parse(String[] args) {
            if (args == null || args.length == 0) {
                return new ParseResult(null, false, "No arguments provided");
            }

            Path configPath = DEFAULT_CONFIG_PATH;
            String repo = null;
            String tag = "latest";
            String digest = null;
            String runtimeId = null;
            String username = null;
            String password = null;
            String passwordEnv = null;
            Path passwordFile = null;
            Path certPath = null;
            Path keyPath = null;
            Path caPath = null;
            boolean showHelp = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> showHelp = true;
                    case "--config" -> configPath = nextPath(args, ++i, "--config");
                    case "--repo" -> repo = nextValue(args, ++i, "--repo");
                    case "--tag", "--ref" -> tag = nextValue(args, ++i, arg);
                    case "--digest" -> digest = nextValue(args, ++i, "--digest");
                    case "--runtime" -> runtimeId = nextValue(args, ++i, "--runtime");
                    case "--username" -> username = nextValue(args, ++i, "--username");
                    case "--password" -> password = nextValue(args, ++i, "--password");
                    case "--password-env" -> passwordEnv = nextValue(args, ++i, "--password-env");
                    case "--password-file" -> passwordFile = nextPath(args, ++i, "--password-file");
                    case "--cert-path" -> certPath = nextPath(args, ++i, "--cert-path");
                    case "--key-path" -> keyPath = nextPath(args, ++i, "--key-path");
                    case "--ca-path" -> caPath = nextPath(args, ++i, "--ca-path");
                    default -> {
                        if (arg.startsWith("-")) {
                            return new ParseResult(null, false, "Unknown option: " + arg);
                        }
                        return new ParseResult(null, false, "Unexpected argument: " + arg);
                    }
                }
            }

            if (showHelp) {
                return new ParseResult(null, true, null);
            }
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

            String reference = digest != null ? digest : tag;

            CliOptions options = new CliOptions(
                    configPath,
                    repo,
                    reference,
                    runtimeId,
                    credentials,
                    certPath,
                    keyPath,
                    caPath
            );
            return new ParseResult(options, false, null);
        }

        private static String nextValue(String[] args, int index, String opt) {
            if (index >= args.length) {
                throw new IllegalArgumentException(opt + " requires a value");
            }
            return args[index];
        }

        private static Path nextPath(String[] args, int index, String opt) {
            return Paths.get(nextValue(args, index, opt));
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

