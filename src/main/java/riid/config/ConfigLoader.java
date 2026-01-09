package riid.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads AppConfig from YAML file.
 */
public final class ConfigLoader {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {}

    public static AppConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Config file not found: " + path);
        }
        try {
            return YAML_MAPPER.readValue(path.toFile(), AppConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from " + path, e);
        }
    }
}

