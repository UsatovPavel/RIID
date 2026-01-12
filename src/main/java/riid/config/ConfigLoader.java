package riid.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads AppConfig from YAML file.
 */
public final class ConfigLoader {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ConfigLoader() { }

    public static AppConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Config file not found: " + path);
        }
        try {
            AppConfig config = YAML_MAPPER.readValue(path.toFile(), AppConfig.class);
            ConfigValidator.validate(config);
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config from " + path, e);
        }
    }
}

