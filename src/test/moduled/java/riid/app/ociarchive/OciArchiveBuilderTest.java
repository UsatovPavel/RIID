package riid.app.ociarchive;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import riid.app.core.model.ImageId;
import riid.cache.oci.ImageDigest;
import riid.client.api.ManifestResult;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;
import riid.core.hash.Sha256Utils;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.MediaType;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.dispatcher.model.RepositoryName;

@Tag("filesystem")
class OciArchiveBuilderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UNRELATED_REGISTRY_DIGEST = "sha256:" + "a".repeat(64);
    private static final String NOT_USED = "Not used";

    @Test
    void indexJsonManifestDigestMatchesActualBlobBytes() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        Path layerFile = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "riid-layer-", ".bin");
        fs.write(layerFile, new byte[]{1, 2, 3});

        Descriptor config = new Descriptor("application/vnd.oci.image.config.v1+json", "sha256:" + "b".repeat(64), 3);
        Descriptor layer = new Descriptor("application/vnd.oci.image.layer.v1.tar+gzip", "sha256:" + "c".repeat(64), 3);
        Manifest manifest = new Manifest(2, "application/vnd.oci.image.manifest.v1+json", config, List.of(layer));
        ManifestResult manifestResult = new ManifestResult(UNRELATED_REGISTRY_DIGEST,
                "application/vnd.oci.image.manifest.v1+json", 0L, manifest);

        RequestDispatcher dispatcher = new FixedLayerDispatcher(layerFile.toString());
        OciArchiveBuilder builder = new OciArchiveBuilder(dispatcher, fs, TestPaths.DEFAULT_BASE_DIR);
        ImageId imageId = ImageId.fromRegistry("registry.example", "library/app", "latest");

        builder.withOciLayout(imageId, manifestResult, ociDir -> {
            byte[] indexBytes = Files.readAllBytes(ociDir.resolve("index.json"));
            JsonNode index = OBJECT_MAPPER.readTree(indexBytes);
            String manifestDigest = index.get("manifests").get(0).get("digest").asText();

            Path blobFile = ociDir.resolve("blobs").resolve("sha256")
                    .resolve(manifestDigest.substring("sha256:".length()));
            assertTrue(Files.isRegularFile(blobFile), "manifest blob referenced by index.json must exist on disk");

            byte[] blobBytes = Files.readAllBytes(blobFile);
            String actualDigest = Sha256Utils.digest(new ByteArrayInputStream(blobBytes));
            assertEquals(actualDigest, manifestDigest,
                    "index.json manifest descriptor digest must match the actual blob content digest");
            return null;
        });
    }

    private static final class FixedLayerDispatcher implements RequestDispatcher {
        private final String path;

        private FixedLayerDispatcher(String path) {
            this.path = path;
        }

        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException(NOT_USED);
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes,
                MediaType mediaType) {
            return new FetchResult(digest, mediaType, Path.of(path));
        }
    }
}
