package riid.app.ociarchive;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import riid.core.model.manifest.MediaTypes;
import riid.core.model.manifest.OciLayout;
import riid.core.model.manifest.TestManifests;
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

        Descriptor config = TestManifests.config(TestManifests.digest('b'), 3);
        Descriptor layer = TestManifests.gzipLayer(TestManifests.digest('c'), 3);
        Manifest manifest = TestManifests.manifest(config, List.of(layer));
        ManifestResult manifestResult = new ManifestResult(UNRELATED_REGISTRY_DIGEST, MediaTypes.OCI_IMAGE_MANIFEST, 0L,
                manifest);

        RequestDispatcher dispatcher = new FixedLayerDispatcher(layerFile.toString());
        OciArchiveBuilder builder = new OciArchiveBuilder(dispatcher, fs, TestPaths.DEFAULT_BASE_DIR);
        ImageId imageId = ImageId.fromRegistry("registry.example", "library/app", "latest");

        builder.withOciLayout(imageId, manifestResult, ociDir -> {
            byte[] indexBytes = Files.readAllBytes(ociDir.resolve(OciLayout.INDEX_JSON));
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

    @Test
    void preservesZstdLayerDescriptorAndCompressedBytes() throws Exception {
        HostFilesystem fs = new NioHostFilesystem();
        byte[] zstdBytes = new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 1, 2, 3};
        Path layerFile = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "riid-zstd-layer-", ".bin");
        fs.write(layerFile, zstdBytes);

        Descriptor config = TestManifests.config(TestManifests.digest('b'), zstdBytes.length);
        Descriptor layer = TestManifests.zstdLayer(TestManifests.digest('c'), zstdBytes.length);
        Manifest manifest = TestManifests.manifest(config, List.of(layer));
        ManifestResult manifestResult = new ManifestResult(UNRELATED_REGISTRY_DIGEST, MediaTypes.OCI_IMAGE_MANIFEST, 0L,
                manifest);

        RequestDispatcher dispatcher = new FixedLayerDispatcher(layerFile.toString());
        OciArchiveBuilder builder = new OciArchiveBuilder(dispatcher, fs, TestPaths.DEFAULT_BASE_DIR);
        ImageId imageId = ImageId.fromRegistry("registry.example", "library/app", "zstd");

        builder.withOciLayout(imageId, manifestResult, ociDir -> {
            JsonNode index = OBJECT_MAPPER.readTree(Files.readAllBytes(ociDir.resolve(OciLayout.INDEX_JSON)));
            String manifestDigest = index.get("manifests").get(0).get("digest").asText();
            Path manifestBlob = ociDir.resolve("blobs").resolve("sha256")
                    .resolve(manifestDigest.substring("sha256:".length()));
            JsonNode writtenManifest = OBJECT_MAPPER.readTree(Files.readAllBytes(manifestBlob));

            assertEquals(MediaTypes.OCI_IMAGE_LAYER_ZSTD,
                    writtenManifest.get("layers").get(0).get("mediaType").asText());
            Path writtenLayer = ociDir.resolve("blobs").resolve("sha256").resolve(layer.digest().substring(7));
            assertArrayEquals(zstdBytes, Files.readAllBytes(writtenLayer),
                    "RIID must hand the registry's zstd blob to Podman byte-for-byte");
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
