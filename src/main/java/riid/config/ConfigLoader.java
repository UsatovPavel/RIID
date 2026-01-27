package riid.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Path;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;

/**
 * Loads AppConfig from YAML file.
 */
public final class ConfigLoader {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ConfigLoader() {
    }

    public static GlobalConfig load(Path path) {
        return load(path, new NioHostFilesystem());
    }

    public static GlobalConfig load(Path path, HostFilesystem fs) {
        if (!fs.exists(path)) {
            throw new IllegalArgumentException("Config file not found: " + path);
        }
        try {
            GlobalConfig config = YAML_MAPPER.readValue(path.toFile(), GlobalConfig.class);
            ConfigValidator.validate(config);
            return config;
        } catch (ConfigValidationException e) {
            throw withPath(e, path);
        } catch (IOException e) {
            Throwable root = e.getCause();
            if (root instanceof ConfigValidationException cve) {
                throw withPath(cve, path);
            }
            if (root instanceof IllegalArgumentException iae) {
                throw new ConfigValidationException("Invalid configuration in " + path + ": " + iae.getMessage(), iae);
            }
            throw new RuntimeException("Failed to load config from " + path, e);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException("Invalid configuration in " + path + ": " + e.getMessage(), e);
        }
    }

    private static ConfigValidationException withPath(ConfigValidationException e, Path path) {
        String msg = e.getMessage();
        if (msg != null && msg.contains(path.toString())) {
            return e;
        }
        return new ConfigValidationException("Invalid configuration in " + path + ": " + msg, e);
    }
}

