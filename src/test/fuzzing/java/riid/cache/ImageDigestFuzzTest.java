package riid.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import riid.cache.oci.ImageDigest;

@Tag("stress")
class ImageDigestFuzzTest {

    @FuzzTest
    void fuzzDigestsDoNotThrowUnexpectedExceptions(FuzzedDataProvider data) {
        String raw = data.consumeString(128);
        try {
            ImageDigest.parse(raw);
        } catch (IllegalArgumentException e) {
            // Expected for invalid inputs.
        } catch (RuntimeException | Error e) {
            recordCrash("ImageDigestFuzzTestInputs", "fuzzDigestsDoNotThrowUnexpectedExceptions", raw);
            throw e;
        }
    }

    @Test
    void randomDigestsDoNotThrowUnexpectedExceptions() {
        Random rnd = new Random(0xDEADBEEF);
        for (int i = 0; i < 20_000; i++) {
            String raw = randomDigest(rnd);
            try {
                ImageDigest.parse(raw);
            } catch (IllegalArgumentException e) {
                // Expected for invalid inputs.
            } catch (RuntimeException e) {
                fail("Unexpected exception for digest: " + raw, e);
            }
        }
    }

    @Test
    void validSha256DigestIsAccepted() {
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        ImageDigest digest = ImageDigest.parse("sha256:" + hex);
        assertEquals("sha256", digest.algorithm());
        assertEquals(hex, digest.hex());
    }

    private static String randomDigest(Random rnd) {
        if (rnd.nextBoolean()) {
            String algorithm = randomAsciiToken(rnd, 1, 12);
            String hex = randomHexLike(rnd, 0, 80);
            return algorithm + (rnd.nextBoolean() ? ":" : "") + hex;
        }
        int len = rnd.nextInt(0, 64);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) rnd.nextInt(33, 127));
        }
        return sb.toString();
    }

    private static String randomAsciiToken(Random rnd, int min, int max) {
        int len = rnd.nextInt(min, max + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = (char) rnd.nextInt('a', 'z' + 1);
            sb.append(c);
        }
        return sb.toString();
    }

    private static String randomHexLike(Random rnd, int min, int max) {
        int len = rnd.nextInt(min, max + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int pick = rnd.nextInt(100);
            if (pick < 80) {
                sb.append((char) rnd.nextInt('0', '9' + 1));
            } else {
                sb.append((char) rnd.nextInt('a', 'f' + 1));
            }
        }
        return sb.toString();
    }

    private static void recordCrash(String testDir, String methodDir, String input) {
        Path dir = Path.of("src", "test", "fuzzing", "resources", "riid", "cache", testDir, methodDir);
        String fileName = "crash-" + Instant.now().toEpochMilli() + "-" + Integer.toHexString(input.hashCode()) + ".txt";
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), input, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort crash capture
        }
    }
}
