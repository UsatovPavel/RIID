package riid.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.ManifestIndex;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("stress")
class ManifestJsonFuzzTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
}



