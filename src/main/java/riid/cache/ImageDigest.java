package riid.cache;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strongly typed image digest with basic validation.
 */
public record ImageDigest(String algorithm, String hex) {
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    public ImageDigest {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(hex, "hex");
        if (algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm is blank");
        }
        if (!HEX64.matcher(hex).matches()) {
            throw new IllegalArgumentException("invalid digest hex");
        }
    }

    public static ImageDigest parse(String raw) {
        Objects.requireNonNull(raw, "digest");
        int idx = raw.indexOf(':');
        if (idx <= 0 || idx == raw.length() - 1) {
            throw new IllegalArgumentException("invalid digest: " + raw);
        }
        String algo = raw.substring(0, idx);
        String hex = raw.substring(idx + 1);
        return new ImageDigest(algo, hex);
    }

    @Override
    public String toString() {
        return algorithm + ":" + hex;
    }
}

