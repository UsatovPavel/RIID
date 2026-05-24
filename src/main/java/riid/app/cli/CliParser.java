package riid.app.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;

import riid.client.core.config.Credentials;

/**
 * Parses CLI arguments and performs basic validation.
 */
public final class CliParser {
    private static final String OPTION_CONFIG = "config";
    private static final String OPTION_DAEMON = "daemon";
    static final Path DEFAULT_CONFIG_PATH = Paths.get("config", "config.yaml");
    private static final String ARG_PATH = "path";
    private static final int MAX_PASSWORD_SOURCES = 1;

    private CliParser() {
    }

    public static ParseResult parse(String[] args) {
        if (args == null || args.length == 0) {
            return new ParseResult(null, false, "No arguments provided");
        }
        Options parsedOptions = new Options();
        parsedOptions.addOption(Option.builder("h").longOpt("help").desc("Show help").build());
        parsedOptions.addOption(Option.builder().longOpt(OPTION_DAEMON).desc("Run as long-lived daemon").build());
        addOption(parsedOptions, OPTION_CONFIG, ARG_PATH);
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
            return new ParseResult(null, false, "Missing value for " + formatOption(e.getOption()));
        } catch (ParseException e) {
            return new ParseResult(null, false, e.getMessage());
        }

        if (!cmd.getArgList().isEmpty()) {
            return new ParseResult(null, false, "Unexpected argument: " + cmd.getArgList().getFirst());
        }
        if (cmd.hasOption("help")) {
            return new ParseResult(null, true, null);
        }

        boolean configProvidedByUser = cmd.hasOption(OPTION_CONFIG);
        Path configPath = Paths.get(cmd.getOptionValue(OPTION_CONFIG, DEFAULT_CONFIG_PATH.toString()));
        boolean daemonMode = cmd.hasOption(OPTION_DAEMON);
        boolean daemonDevInternalErrorProbe = cmd.hasOption("daemon-dev-internal-error-probe");
        if (daemonDevInternalErrorProbe && !daemonMode) {
            return new ParseResult(null, false, "--daemon-dev-internal-error-probe requires --daemon");
        }
        String repo = cmd.getOptionValue("repo");
        String tag = cmd.getOptionValue("tag");
        String ref = cmd.getOptionValue("ref");
        String digest = cmd.getOptionValue("digest");
        String runtimeId = cmd.getOptionValue("runtime");
        String username = cmd.getOptionValue("username");
        String password = cmd.getOptionValue("password");
        String passwordEnv = cmd.getOptionValue("password-env");
        Path passwordFile = cmd.hasOption("password-file") ? Paths.get(cmd.getOptionValue("password-file")) : null;
        Path certPath = cmd.hasOption("cert-path") ? Paths.get(cmd.getOptionValue("cert-path")) : null;
        Path keyPath = cmd.hasOption("key-path") ? Paths.get(cmd.getOptionValue("key-path")) : null;
        Path caPath = cmd.hasOption("ca-path") ? Paths.get(cmd.getOptionValue("ca-path")) : null;
        if (!daemonMode && (repo == null || repo.isBlank())) {
            return new ParseResult(null, false, "Repository is required (--repo)");
        }
        if (!daemonMode && (runtimeId == null || runtimeId.isBlank())) {
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
        String reference = digest != null ? digest : (tag != null ? tag : (ref != null ? ref : "latest"));

        CliOptions cliOptions = new CliOptions(configPath, configProvidedByUser, daemonMode, repo, reference, runtimeId,
                credentials, certPath, keyPath, caPath);
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
        options.addOption(Option.builder().longOpt(longOpt).hasArg().argName(argName).build());
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

    public record CliOptions(Path configPath, boolean configProvidedByUser, boolean daemonMode, String repository,
            String reference, String runtimeId, Credentials credentials, Path certPath, Path keyPath, Path caPath) {
        public boolean hasCerts() {
            return certPath != null || keyPath != null || caPath != null;
        }
    }

    public record ParseResult(CliOptions options, boolean showHelp, String errorMessage) {
    }
}
