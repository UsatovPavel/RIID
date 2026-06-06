package riid.core.logging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

/**
 * 7.4 Redaction: secrets must not appear raw in log lines; masking yields
 * [REDACTED].
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class LogRedactionTest {

    private static final String SECRET_PASSWORD = "p4ssw0rd_unit_test_leak";
    private static final String SECRET_TOKEN = "tok_unit_test_leak_abc";
    private static final String SECRET_BEARER = "Bearer unit_test_bearer_leak";

    private static final String BEARER_REGEX = "(?i)(bearer\\s+)[-A-Za-z0-9._~+/]+=*";
    private static final String BEARER_MASK = "$1[REDACTED]";
    private static final String KV_REGEX = "(?i)(authorization|password|token|identityToken)=([^\\s&]+)";
    private static final String KV_MASK = "$1=[REDACTED]";

    @Test
    void structuredLogJsonDoesNotContainRawSecrets() throws Exception {
        Path logFile = Files.createTempFile("redaction-log", ".ndjson");
        logFile.toFile().deleteOnExit();

        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putProperty("redaction.log.path", logFile.toAbsolutePath().toString().replace('\\', '/'));

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (InputStream cfg = LogRedactionTest.class.getResourceAsStream("/logback-redaction-test.xml")) {
            if (cfg == null) {
                throw new IllegalStateException("Missing classpath resource logback-redaction-test.xml");
            }
            configurator.doConfigure(cfg);
        }
        context.start();
        try {
            Logger logger = context.getLogger("redaction-test");
            logger.atInfo().addKeyValue("password", SECRET_PASSWORD).addKeyValue("token", SECRET_TOKEN)
                    .addKeyValue("authorization", SECRET_BEARER).log("synthetic milestone");
        } finally {
            context.stop();
        }

        String line = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8).trim();
        assertFalse(line.isEmpty(), line);
        assertFalse(line.contains(SECRET_PASSWORD), line);
        assertFalse(line.contains(SECRET_TOKEN), line);
        assertFalse(line.contains("unit_test_bearer_leak"), line);
        assertTrue(line.contains("[REDACTED]"), line);
    }

    @Test
    void exceptionStyleMessagesDoNotLeakSecretsAfterValueMasking() throws IOException {
        String raw = "failed: password=" + SECRET_PASSWORD + " token=" + SECRET_TOKEN + " " + SECRET_BEARER;
        String masked = applyValueMasks(raw);
        assertFalse(masked.contains(SECRET_PASSWORD));
        assertFalse(masked.contains(SECRET_TOKEN));
        assertFalse(masked.contains("unit_test_bearer_leak"));
    }

    private static String applyValueMasks(String input) {
        String step1 = input.replaceAll(BEARER_REGEX, BEARER_MASK);
        return step1.replaceAll(KV_REGEX, KV_MASK);
    }
}
