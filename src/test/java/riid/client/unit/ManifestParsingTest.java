package riid.client.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.ManifestIndex;

import static org.junit.jupiter.api.Assertions.*;

class ManifestParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesManifest() throws Exception {
        String json = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": 7023,
                    "digest": "sha256:aaa"
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": 32654,
                      "digest": "sha256:bbb"
                    }
                  ]
                }
                """;
        Manifest m = mapper.readValue(json, Manifest.class);
        assertEquals(2, m.schemaVersion());
        assertEquals("sha256:aaa", m.config().digest());
        assertEquals(1, m.layers().size());
        assertEquals("sha256:bbb", m.layers().getFirst().digest());
    }

    @Test
    void parsesManifestIndex() throws Exception {
        String json = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.list.v2+json",
                  "manifests": [
                    {
                      "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                      "size": 7143,
                      "digest": "sha256:manifest1",
                      "platform": {
                        "architecture": "amd64",
                        "os": "linux"
                      }
                    }
                  ]
                }
                """;
        ManifestIndex idx = mapper.readValue(json, ManifestIndex.class);
        assertEquals(1, idx.manifests().size());
        assertEquals("sha256:manifest1", idx.manifests().getFirst().digest());
        assertEquals("linux", idx.manifests().getFirst().platform().os());
    }
}

