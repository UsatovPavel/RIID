package riid.core.logging;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LogRedactorTest {
    private static final String PROP = "riid.log.redaction.enabled";

    @Test
    void sanitizeTextRedactsBearerAndSecretPairs() {
        String raw = "Authorization: Bearer abc.def token=my-secret password=qwerty";
        String sanitized = LogRedactor.sanitizeText(raw);

        Assertions.assertFalse(sanitized.contains("abc.def"));
        Assertions.assertFalse(sanitized.contains("my-secret"));
        Assertions.assertFalse(sanitized.contains("qwerty"));
        Assertions.assertTrue(sanitized.contains("[REDACTED]"));
    }

    @Test
    void sanitizeUrlDropsSensitiveQuery() {
        String raw = "https://registry.local/v2/repo/blobs/sha256:123?token=secret&x=1";
        String sanitized = LogRedactor.sanitizeUrl(raw);

        Assertions.assertEquals("https://registry.local/v2/repo/blobs/sha256:123", sanitized);
        Assertions.assertFalse(sanitized.contains("secret"));
    }

    @Test
    void sanitizeTextReturnsRawWhenDisabledByProperty() {
        String previous = System.getProperty(PROP);
        System.setProperty(PROP, "false");
        try {
            String raw = "Authorization: Bearer abc.def token=my-secret";
            String sanitized = LogRedactor.sanitizeText(raw);
            Assertions.assertEquals(raw, sanitized);
        } finally {
            restoreProperty(previous);
        }
    }

    private static void restoreProperty(String value) {
        if (value == null) {
            System.clearProperty(PROP);
            return;
        }
        System.setProperty(PROP, value);
    }
}
