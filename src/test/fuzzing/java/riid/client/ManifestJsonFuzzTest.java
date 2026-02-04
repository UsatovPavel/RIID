package riid.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;

import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.ManifestIndex;

@Tag("stress")
class ManifestJsonFuzzTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @FuzzTest
    void fuzzJsonDoesNotThrowUnexpectedExceptions(FuzzedDataProvider data) {
        String json = data.consumeString(512);
        try {
            mapper.readValue(json, Manifest.class);
            mapper.readValue(json, ManifestIndex.class);
        } catch (IOException e) {
            // Expected for malformed or incompatible JSON.
        } catch (RuntimeException | Error e) {
            recordCrash("ManifestJsonFuzzTestInputs", "fuzzJsonDoesNotThrowUnexpectedExceptions", json);
            throw e;
        }
    }

    @Test
    void randomJsonDoesNotThrowUnexpectedExceptions() {
        Random rnd = new Random(0xBADC0FFE);
        for (int i = 0; i < 5_000; i++) {
            String json = randomJson(rnd);
            try {
                mapper.readValue(json, Manifest.class);
                mapper.readValue(json, ManifestIndex.class);
            } catch (IOException e) {
                // Expected for malformed or incompatible JSON.
            } catch (RuntimeException e) {
                fail("Unexpected exception for json: " + json, e);
            }
        }
    }

    @Test
    void minimalValidJsonParses() {
        String manifestJson = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": 7023,
                    "digest": "sha256:aaa"
                  },
                  "layers": []
                }
                """;
        String indexJson = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.list.v2+json",
                  "manifests": []
                }
                """;
        assertDoesNotThrow(() -> mapper.readValue(manifestJson, Manifest.class));
        assertDoesNotThrow(() -> mapper.readValue(indexJson, ManifestIndex.class));
    }

    private static String randomJson(Random rnd) {
        int len = rnd.nextInt(0, 512);
        StringBuilder sb = new StringBuilder(len + 10);
        if (rnd.nextBoolean()) {
            sb.append("{");
        }
        for (int i = 0; i < len; i++) {
            int pick = rnd.nextInt(100);
            char c;
            if (pick < 70) {
                c = (char) rnd.nextInt(32, 127);
            } else if (pick < 85) {
                char[] tokens = new char[] { '{', '}', '[', ']', ':', ',', '"', '\\' };
                c = tokens[rnd.nextInt(tokens.length)];
            } else {
                c = rnd.nextBoolean() ? ' ' : '\n';
            }
            sb.append(c);
        }
        if (rnd.nextBoolean()) {
            sb.append("}");
        }
        return sb.toString();
    }

    private static void recordCrash(String testDir, String methodDir, String input) {
        Path dir = Path.of("src", "test", "fuzzing", "resources", "riid", "client", testDir, methodDir);
        String fileName = "crash-" + Instant.now().toEpochMilli() + "-" + Integer.toHexString(input.hashCode()) + ".json";
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), input, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort crash capture
        }
    }
}
