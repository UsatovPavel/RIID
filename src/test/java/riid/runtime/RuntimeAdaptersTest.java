package riid.runtime;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeAdaptersTest {

    private static final String TAR_SUFFIX = ".tar";
    private static final String PAYLOAD = "data";
    private static final String ERR = "err";

    @Test
    void dockerFailsOnMissingFile() {
        DockerRuntimeAdapter adapter = new DockerRuntimeAdapter();
        Path missing = Path.of("non-existent-docker-image.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    void dockerThrowsOnNonZeroExit() throws Exception {
        Path tmp = createMinimalOciArchive();
        DockerRuntimeAdapter adapter = new TestDockerAdapter(2, "o", ERR);
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "docker load failed");
        assertContains(ex.getMessage(), ERR);
    }

    @Test
    void dockerSuccess() throws Exception {
        Path tmp = createMinimalOciArchive();
        DockerRuntimeAdapter adapter = new TestDockerAdapter(0, "ok", "");
        assertDoesNotThrow(() -> adapter.importImage(tmp));
    }

    @Test
    void dockerUntarFailurePropagates() throws Exception {
        Path tmp = Files.createTempFile("bad-tar-", TAR_SUFFIX);
        Files.writeString(tmp, "not a tar archive");
        DockerRuntimeAdapter adapter = new DockerRuntimeAdapter();
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "Failed to unpack OCI archive");
    }

    @Test
    void dockerTarFailurePropagates() throws Exception {
        Path tmp = createMinimalOciArchive();
        DockerRuntimeAdapter adapter = new DockerRuntimeAdapter() {
            @Override
            protected void tar(Path sourceDir, Path destTar) throws IOException {
                throw new IOException("Failed to create docker archive: injected");
            }
        };
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "Failed to create docker archive");
    }

    @Test
    void dockerRuntimeId() {
        assertEquals("podman", new PodmanRuntimeAdapter().runtimeId());
        assertEquals("docker", new DockerRuntimeAdapter().runtimeId());
    }

    @Test
    void podmanFailsOnMissingFile() {
        PodmanRuntimeAdapter adapter = new PodmanRuntimeAdapter();
        Path missing = Path.of("non-existent-image.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    void podmanThrowsOnNonZeroExit() throws Exception {
        Path tmp = Files.createTempFile("podman-", TAR_SUFFIX);
        Files.writeString(tmp, PAYLOAD);
        PodmanRuntimeAdapter adapter = new TestPodmanAdapter(1, "out", ERR);
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "podman load failed");
        assertContains(ex.getMessage(), ERR);
    }

    @Test
    void podmanSuccess() throws Exception {
        Path tmp = Files.createTempFile("podman-", TAR_SUFFIX);
        Files.writeString(tmp, PAYLOAD);
        PodmanRuntimeAdapter adapter = new TestPodmanAdapter(0, "ok", "");
        assertDoesNotThrow(() -> adapter.importImage(tmp));
    }

    @Test
    void podmanRuntimeId() {
        assertEquals("podman", new PodmanRuntimeAdapter().runtimeId());
    }

    @Test
    void portoFailsOnMissingFile() {
        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        Path missing = Path.of("non-existent-porto.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    void portoThrowsOnNonZeroExit() throws Exception {
        Path tmp = Files.createTempFile("porto-", TAR_SUFFIX);
        Files.writeString(tmp, PAYLOAD);
        PortoRuntimeAdapter adapter = new TestPortoAdapter(2, "o", ERR);
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "portoctl layer import failed");
        assertContains(ex.getMessage(), ERR);
    }

    @Test
    void portoSuccess() throws Exception {
        Path tmp = Files.createTempFile("porto-", TAR_SUFFIX);
        Files.writeString(tmp, PAYLOAD);
        PortoRuntimeAdapter adapter = new TestPortoAdapter(0, "ok", "");
        assertDoesNotThrow(() -> adapter.importImage(tmp));
    }

    private static void assertContains(String msg, String fragment) {
        if (msg == null || !msg.contains(fragment)) {
            throw new AssertionError("Expected \"" + fragment + "\" in: " + msg);
        }
    }

    private static Path createMinimalOciArchive() throws Exception {
        Path ociDir = Files.createTempDirectory("oci-test");
        Path blobs = ociDir.resolve("blobs").resolve("sha256");
        Files.createDirectories(blobs);

        ObjectMapper mapper = new ObjectMapper();

        byte[] configBytes = "{}".getBytes(StandardCharsets.UTF_8);
        String configDigest = sha256(configBytes);
        Files.write(blobs.resolve(configDigest), configBytes);

        byte[] layerBytes = "layer".getBytes(StandardCharsets.UTF_8);
        String layerDigest = sha256(layerBytes);
        Files.write(blobs.resolve(layerDigest), layerBytes);

        var manifest = new riid.client.core.model.manifest.Manifest(
                2,
                "application/vnd.oci.image.manifest.v1+json",
                new riid.client.core.model.manifest.Descriptor(
                        "application/vnd.oci.image.config.v1+json",
                        "sha256:" + configDigest,
                        configBytes.length),
                List.of(new riid.client.core.model.manifest.Descriptor(
                        "application/vnd.oci.image.layer.v1.tar",
                        "sha256:" + layerDigest,
                        layerBytes.length))
        );
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
        String manifestDigest = sha256(manifestBytes);
        Files.write(blobs.resolve(manifestDigest), manifestBytes);

        Files.writeString(ociDir.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");

        ObjectNode manifestNode = mapper.createObjectNode();
        manifestNode.put("mediaType", "application/vnd.oci.image.manifest.v1+json");
        manifestNode.put("size", manifestBytes.length);
        manifestNode.put("digest", "sha256:" + manifestDigest);
        ObjectNode annotations = mapper.createObjectNode();
        annotations.put("org.opencontainers.image.ref.name", "docker.io/library/test:latest");
        manifestNode.set("annotations", annotations);

        ArrayNode manifests = mapper.createArrayNode();
        manifests.add(manifestNode);
        ObjectNode index = mapper.createObjectNode();
        index.put("schemaVersion", 2);
        index.set("manifests", manifests);
        mapper.writeValue(ociDir.resolve("index.json").toFile(), index);

        Path tar = Files.createTempFile("oci-archive", TAR_SUFFIX);
        runTar(tar, ociDir);
        return tar;
    }

    private static void runTar(Path archive, Path ociDir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-cf", archive.toString(), "-C", ociDir.toString(), ".")
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("tar failed: " + out);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        return HexFormat.of().formatHex(digest);
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestDockerAdapter extends DockerRuntimeAdapter {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private TestDockerAdapter(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        protected Process startProcess(List<String> command) {
            return new FakeProcess(exitCode, stdout, stderr);
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestPodmanAdapter extends PodmanRuntimeAdapter {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private TestPodmanAdapter(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        protected Process startProcess(List<String> command) {
            return new FakeProcess(exitCode, stdout, stderr);
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestPortoAdapter extends PortoRuntimeAdapter {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private TestPortoAdapter(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        protected Process startProcess(List<String> command) {
            return new FakeProcess(exitCode, stdout, stderr);
        }
    }

    private static final class FakeProcess extends Process {
        private final int exitCode;
        private final byte[] out;
        private final byte[] err;

        private FakeProcess(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.out = stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            this.err = stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(out);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(err);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            // no-op
        }
    }
}

