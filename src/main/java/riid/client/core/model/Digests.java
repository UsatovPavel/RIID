package riid.client.core.model;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * Digest utilities.
 */
public final class Digests {
    private Digests() {
    }

    public static String sha256Hex(byte[] data) {
        return DigestUtils.sha256Hex(data);
    }

    public static String sha256Hex(InputStream is) throws IOException {
        return DigestUtils.sha256Hex(is);
    }
}

