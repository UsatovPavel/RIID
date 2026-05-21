package riid.app.cli;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class CliParserTest {
    private static final String RUNTIME_PODMAN = "podman";
    private static final String REPO_BUSYBOX = "library/busybox";

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
}
