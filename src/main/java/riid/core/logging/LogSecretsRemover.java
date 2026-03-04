package riid.core.logging;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Best-effort sanitizer for values that may contain credentials.
 */
public final class LogSecretsRemover {
    private static final String NONE = "none";
    private static final String REDACTED = "[REDACTED]";
    private static final String REDACTION_ENABLED_PROPERTY = "riid.log.redaction.enabled";
    private static final Pattern AUTH_BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern AUTH_BASIC = Pattern.compile("(?i)Basic\\s+[A-Za-z0-9+/=]+");
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)(token|password|passwd|secret|authorization)(=|:)[^\\s&,;]+");

    private LogSecretsRemover() {
    }

    public static String sanitizeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        if (!redactionEnabled()) {
            return raw;
        }
        String value = raw;
        value = AUTH_BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        value = AUTH_BASIC.matcher(value).replaceAll("Basic " + REDACTED);
        return SECRET_PARAM.matcher(value).replaceAll("$1$2" + REDACTED);
    }

    public static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return NONE;
        }
        if (!redactionEnabled()) {
            return rawUrl;
        }
        try {
            URI uri = URI.create(rawUrl);
            if (uri.getHost() == null) {
                return sanitizeText(rawUrl);
            }
            StringBuilder out = new StringBuilder();
            if (uri.getScheme() != null) {
                out.append(uri.getScheme()).append("://");
            }
            out.append(uri.getHost());
            if (uri.getPort() >= 0) {
                out.append(":").append(uri.getPort());
            }
            if (uri.getPath() != null && !uri.getPath().isBlank()) {
                out.append(uri.getPath());
            }
            return sanitizeText(out.toString());
        } catch (Exception ignored) {
            return sanitizeText(rawUrl);
        }
    }

    public static String sanitizePath(Path path) {
        if (path == null) {
            return NONE;
        }
        if (!redactionEnabled()) {
            return path.toString();
        }
        Path fileName = path.getFileName();
        if (fileName == null) {
            return sanitizeText(path.toString());
        }
        return sanitizeText(Objects.toString(fileName, NONE));
    }

    private static boolean redactionEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(REDACTION_ENABLED_PROPERTY, "true"));
    }
}
