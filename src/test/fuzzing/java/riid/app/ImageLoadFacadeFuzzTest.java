package riid.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import riid.app.error.AppException;
import riid.app.fs.HostFilesystem;
import riid.cache.oci.ImageDigest;
import riid.client.api.ManifestResult;
import riid.client.api.RegistryClient;
import riid.client.core.model.manifest.Descriptor;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.MediaType;
import riid.client.core.model.manifest.TagList;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.dispatcher.model.RepositoryName;
import riid.runtime.RuntimeAdapter;

@Tag("stress")
class ImageLoadFacadeFuzzTest {

    @Test
    void randomInputsDoNotCrash() {
        Arbitrary<String> registryArb = registry();
        Arbitrary<String> nameArb = name();
        Arbitrary<String> referenceArb = reference();
        Arbitrary<String> hexArb = hex64();
        Arbitrary<Integer> configSizeArb = Arbitraries.integers().between(0, 1024);
        Arbitrary<Integer> layerSizeArb = Arbitraries.integers().between(0, 1024);
        Arbitrary<Integer> contentLengthArb = Arbitraries.integers().between(0, 4096);

        for (int i = 0; i < 1000; i++) {
            String registry = registryArb.sample();
            String name = nameArb.sample();
            String reference = referenceArb.sample();
            String manifestHex = hexArb.sample();
            String configHex = hexArb.sample();
            String layerHex = hexArb.sample();
            int configSize = configSizeArb.sample();
            int layerSize = layerSizeArb.sample();
            int contentLength = contentLengthArb.sample();

            ImageId imageId;
            try {
                imageId = ImageId.fromRegistry(registry, name, reference);
            } catch (IllegalArgumentException e) {
                continue; // invalid input is expected
            }

            String manifestDigest = "sha256:" + manifestHex;
            String configDigest = "sha256:" + configHex;
            String layerDigest = "sha256:" + layerHex;
            Descriptor config = new Descriptor(MediaType.OCI_IMAGE_CONFIG.value(), configDigest, configSize);
            Descriptor layer = new Descriptor(MediaType.OCI_IMAGE_LAYER_GZIP.value(), layerDigest, layerSize);
            Manifest manifest = new Manifest(
                    2,
                    MediaType.OCI_IMAGE_MANIFEST.value(),
                    config,
                    List.of(layer)
            );
            ManifestResult manifestResult = new ManifestResult(
                    manifestDigest,
                    MediaType.OCI_IMAGE_MANIFEST.value(),
                    contentLength,
                    manifest
            );

            RequestDispatcher dispatcher = new NoopDispatcher();
            RegistryClient client = new NoopRegistryClient();
            RuntimeAdapter runtime = new NoopRuntime();
            HostFilesystem fs = new FailingHostFilesystem(new IOException("fuzz"));

            try (ImageLoadingFacade facade = new ImageLoadingFacade(
                    dispatcher,
                    new RuntimeRegistry(Map.of("noop", runtime)),
                    client,
                    fs
            )) {
                facade.load(manifestResult, runtime, imageId);
                fail("Expected AppException due to failing filesystem");
            } catch (AppException e) {
                // Expected: filesystem failure should be wrapped.
            } catch (Exception e) {
                fail("Unexpected exception type", e);
            }
        }
    }

    Arbitrary<String> registry() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789.-")
                .ofMinLength(0)
                .ofMaxLength(40);
    }

    Arbitrary<String> name() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789.-_/")
                .ofMinLength(0)
                .ofMaxLength(80);
    }

    Arbitrary<String> reference() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789.-_:@")
                .ofMinLength(0)
                .ofMaxLength(80);
    }

    Arbitrary<String> hex64() {
        return Arbitraries.strings()
                .withChars("0123456789abcdef")
                .ofMinLength(64)
                .ofMaxLength(64);
    }

    private static final class NoopRegistryClient implements RegistryClient {
        @Override
        public ManifestResult fetchManifest(String repository, String reference) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public riid.client.api.BlobResult fetchConfig(String repository, Manifest manifest, File target) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public riid.client.api.BlobResult fetchBlob(riid.client.api.BlobRequest request, File target) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Optional<Long> headBlob(String repository, String digest) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public TagList listTags(String repository, Integer n, String last) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void close() { }
    }

    private static final class NoopDispatcher implements RequestDispatcher {
        @Override
        public FetchResult fetchImage(ImageRef ref) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public FetchResult fetchLayer(RepositoryName repository, ImageDigest digest, long sizeBytes, MediaType mediaType) {
            throw new UnsupportedOperationException("not used");
        }

    }

    private static final class NoopRuntime implements RuntimeAdapter {
        @Override
        public String runtimeId() {
            return "noop";
        }

        @Override
        public void importImage(Path imagePath) {
            // not used (filesystem fails before import)
        }
    }

    private static final class FailingHostFilesystem implements HostFilesystem {
        private final IOException error;

        private FailingHostFilesystem(IOException error) {
            this.error = error;
        }

        @Override
        public Path createDirectory(Path dir) throws IOException {
            throw error;
        }

        @Override
        public Path createFile(Path path) throws IOException {
            throw error;
        }

        @Override
        public Path copy(Path source, Path target, CopyOption... options) throws IOException {
            throw error;
        }

        @Override
        public Path write(Path path, byte[] bytes, OpenOption... options) throws IOException {
            throw error;
        }

        @Override
        public Path writeString(Path path, String content, OpenOption... options) throws IOException {
            throw error;
        }

        @Override
        public InputStream newInputStream(Path path) throws IOException {
            throw error;
        }

        @Override
        public OutputStream newOutputStream(Path path) throws IOException {
            throw error;
        }

        @Override
        public boolean exists(Path path) {
            error.getClass();
            return false;
        }

        @Override
        public boolean isRegularFile(Path path) {
            error.getClass();
            return false;
        }

        @Override
        public long size(Path path) throws IOException {
            throw error;
        }

        @Override
        public String probeContentType(Path path) throws IOException {
            throw error;
        }

        @Override
        public Stream<Path> walk(Path root) throws IOException {
            throw error;
        }

        @Override
        public Path atomicMove(Path source, Path target) throws IOException {
            throw error;
        }
    }
}
