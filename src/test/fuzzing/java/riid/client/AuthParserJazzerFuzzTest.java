package riid.client;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;
import riid.client.core.model.auth.AuthParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Tag("stress")
class AuthParserJazzerFuzzTest {

    @FuzzTest
    void fuzzAuthHeader(FuzzedDataProvider data) {
        String header = data.consumeString(512);
        try {
            AuthParser.parse(header);
        } catch (RuntimeException | Error e) {
            recordCrash("AuthParserJazzerFuzzTestInputs", "fuzzAuthHeader", header);
            throw e;
        }
    }

    private static void recordCrash(String testDir, String methodDir, String input) {
        Path dir = Path.of("src", "test", "fuzzing", "resources", "riid", "client", testDir, methodDir);
        String fileName = "crash-" + Instant.now().toEpochMilli() + "-" + Integer.toHexString(input.hashCode()) + ".txt";
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), input, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort crash capture
        }
    }
}
