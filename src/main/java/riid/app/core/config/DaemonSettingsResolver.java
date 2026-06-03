package riid.app.core.config;

import java.nio.file.Files;

import riid.app.cli.CliParser;
import riid.core.config.ConfigLoader;
import riid.core.config.GlobalConfig;

/**
 * Resolves daemon settings from {@link GlobalConfig}.
 */
public final class DaemonSettingsResolver {
    private DaemonSettingsResolver() {
    }

    public static AppConfig.DaemonConfig resolve(CliParser.CliOptions options) throws Exception {
        if (!options.configProvidedByUser() && !Files.exists(options.configPath())) {
            return new AppConfig(null, null, null, null).daemonOrDefault();
        }
        GlobalConfig config = ConfigLoader.load(options.configPath());
        if (config.app() == null || config.app().daemon() == null) {
            return new AppConfig(null, null, null, null).daemonOrDefault();
        }
        return config.app().daemon();
    }
}
