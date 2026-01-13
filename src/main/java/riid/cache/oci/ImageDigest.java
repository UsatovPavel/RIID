package riid.cache.oci;

import java.util.List;
import java.util.Objects;

/**
 * Strongly typed image digest with basic validation.
 */
public record ImageDigest(String algorithm, String hex) {
    private static final int HEX_LENGTH = 64;
    private static final List<String> SUPPORTED_ALGORITHMS = List.of("sha256");

    public ImageDigest {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(hex, "hex");
        if (algorithm.isBlank()) {
            throw new ValidationException("algorithm is blank");
        }
        if (!SUPPORTED_ALGORITHMS.contains(algorithm)) {
            throw new ValidationException("unsupported digest algorithm: " + algorithm);
        }
        validateHex(hex);
    }

    public static ImageDigest parse(String raw) {
        Objects.requireNonNull(raw, "digest");
        int idx = raw.indexOf(':');
        if (idx <= 0 || idx == raw.length() - 1) {
            throw new ValidationException("invalid digest: " + raw);
        }
        String algo = raw.substring(0, idx);
        String hex = raw.substring(idx + 1);
        return new ImageDigest(algo, hex);
    }

    private static void validateHex(String hex) {
        if (hex.length() != HEX_LENGTH) {
            throw new ValidationException("invalid digest hex length");
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                throw new ValidationException("invalid digest hex character at position " + i);
            }
        }
    }

    @Override
    public String toString() {
        return algorithm + ":" + hex;
    }
}

