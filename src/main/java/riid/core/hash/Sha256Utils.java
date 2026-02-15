package riid.core.hash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helpers for streaming IO.
 */
public final class Sha256Utils {
    private static final String PREFIX = "sha256:";

    private Sha256Utils() {
    }

    public static String digest(InputStream input) throws IOException {
        MessageDigest md = newDigest();
        try (DigestInputStream digestStream = new DigestInputStream(input, md)) {
            digestStream.transferTo(OutputStream.nullOutputStream());
        }
        return toPrefixedHex(md.digest());
    }

    public static String copyAndDigest(InputStream input, OutputStream output) throws IOException {
        MessageDigest md = newDigest();
        try (DigestInputStream digestStream = new DigestInputStream(input, md)) {
            digestStream.transferTo(output);
        }
        return toPrefixedHex(md.digest());
    }

    public static String toPrefixedHex(byte[] bytes) {
        return PREFIX + toHex(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
