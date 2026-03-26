package riid.core.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class LogbackMaskingRulesTest {
    private static final String BEARER_REGEX = "(?i)(bearer\\s+)[-A-Za-z0-9._~+/]+=*";
    private static final String BEARER_MASK = "$1[REDACTED]";
    private static final String KV_REGEX = "(?i)(authorization|password|token|identityToken)=([^\\s&]+)";
    private static final String KV_MASK = "$1=[REDACTED]";

    @Test
    void logbackConfigContainsMaskingDecoratorAndPaths() throws IOException {
        String root = Files.readString(Path.of("src", "main", "resources", "logback.xml"));
        assertTrue(root.contains("include resource=\"logback-encoder-masking.xml\""));
        assertTrue(root.contains("appender-ref ref=\"STDOUT_JSON\""));

        String fragment = Files.readString(Path.of("src", "main", "resources", "logback-encoder-masking.xml"));
        assertTrue(fragment.contains("ConsoleAppender"));
        assertTrue(fragment.contains("LogstashEncoder"));
        assertTrue(fragment.contains("MaskingJsonGeneratorDecorator"));
        assertTrue(fragment.contains("<path>authorization</path>"));
        assertTrue(fragment.contains("<path>password</path>"));
        assertTrue(fragment.contains("<path>token</path>"));
        assertTrue(fragment.contains("<path>identityToken</path>"));
    }

    @Test
    void regexRulesMaskSecretsAndKeepOrdinaryTextReadable() {
        String withBearer = "Authorization: Bearer superSecretTokenValue";
        String maskedBearer = withBearer.replaceAll(BEARER_REGEX, BEARER_MASK);
        assertTrue(maskedBearer.contains("Bearer [REDACTED]"));

        String withKv = "token=abc123 password=qwerty authorization=BasicZXhhbXBsZQ==";
        String maskedKv = withKv.replaceAll(KV_REGEX, KV_MASK);
        assertTrue(maskedKv.contains("token=[REDACTED]"));
        assertTrue(maskedKv.contains("password=[REDACTED]"));
        assertTrue(maskedKv.contains("authorization=[REDACTED]"));

        String normalLine = "event=manifest.fetch result=success duration_ms=15";
        String once = normalLine.replaceAll(BEARER_REGEX, BEARER_MASK);
        String twice = once.replaceAll(KV_REGEX, KV_MASK);
        assertEquals(normalLine, twice);
    }
}
