package riid.fuzz;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.client.core.model.auth.AuthParser;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("stress")
class AuthParserFuzzTest {

    @Test
    void randomHeadersDoNotThrow() {
        Random rnd = new Random(0xC0FFEE);
        for (int i = 0; i < 20_000; i++) {
            String header = randomHeader(rnd);
            assertDoesNotThrow(() -> AuthParser.parse(header));
        }
    }

    private static String randomHeader(Random rnd) {
        int len = rnd.nextInt(0, 512);
        StringBuilder sb = new StringBuilder(len);

        // Bias towards inputs that look like real headers, but still allow arbitrary garbage.
        if (rnd.nextInt(10) == 0) {
            sb.append("Bearer ");
        } else if (rnd.nextInt(10) == 0) {
            sb.append("Basic ");
        }

        for (int i = 0; i < len; i++) {
            int pick = rnd.nextInt(100);
            char c;
            if (pick < 70) {
                // Visible ASCII excluding control chars.
                c = (char) rnd.nextInt(32, 127);
            } else if (pick < 85) {
                // Whitespace noise.
                c = rnd.nextBoolean() ? ' ' : '\t';
            } else if (pick < 95) {
                // Characters common in WWW-Authenticate challenges.
                char[] common = new char[] { '=', ',', '"', ':', '/', '.', '-', '_' };
                c = common[rnd.nextInt(common.length)];
            } else {
                // A small set of less common ASCII punctuation.
                char[] weird = new char[] { '~', '^', '`', '|', '{', '}', '[', ']', '\\' };
                c = weird[rnd.nextInt(weird.length)];
            }
            sb.append(c);
        }
        return sb.toString();
    }
}


