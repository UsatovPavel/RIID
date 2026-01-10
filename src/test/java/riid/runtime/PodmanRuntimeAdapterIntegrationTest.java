package riid.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import riid.cache.TokenCache;
import riid.client.api.BlobRequest;
import riid.client.api.RegistryClient;
import riid.client.api.RegistryClientImpl;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.service.AuthService;
import riid.client.service.BlobService;
import riid.client.service.ManifestService;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class PodmanRuntimeAdapterIntegrationTest {

    private static final String REPO = "library/alpine";
    private static final String REF = "edge";

    @Test
    void downloadsImageAndLoadsIntoPodman() throws Exception {
        // Clean up image if already present
        runIgnoreErrors(List.of("podman", "rmi", "-f", "alpine:edge"));

        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
        HttpClientConfig httpConfig = new HttpClientConfig();
        var httpClient = HttpClientFactory.create(httpConfig);
        HttpExecutor http = new HttpExecutor(httpClient, httpConfig);
        ObjectMapper mapper = new ObjectMapper();
        AuthService authService = new AuthService(http, mapper, new TokenCache());
        ManifestService manifestService = new ManifestService(http, authService, mapper);
        BlobService blobService = new BlobService(http, authService);
        RegistryClient client = new RegistryClientImpl(endpoint, httpConfig, null);

        // Fetch manifest
        var manifestResult = manifestService.fetchManifest(endpoint, REPO, REF, "repository:%s:pull".formatted(REPO));
        Manifest manifest = manifestResult.manifest();

        Path ociDir = Files.createTempDirectory("oci-layout");
        Path blobsDir = ociDir.resolve("blobs").resolve("sha256");
        Files.createDirectories(blobsDir);

        // Write config blob
        var cfg = manifest.config();
        Path cfgPath = downloadBlob(blobService, endpoint, cfg.digest(), cfg.size(), cfg.mediaType(), ociDir);

        // Write layer blobs
        for (var layer : manifest.layers()) {
            downloadBlob(blobService, endpoint, layer.digest(), layer.size(), layer.mediaType(), ociDir);
        }

        // Write manifest blob
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
        String manifestDigest = sha256Hex(manifestBytes);
        Path manifestBlob = blobsDir.resolve(manifestDigest);
        Files.write(manifestBlob, manifestBytes);

        // oci-layout
        Files.writeString(ociDir.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");

        // index.json
        String refName = "docker.io/%s:%s".formatted(REPO, REF);
        String index = """
                {
                  "schemaVersion": 2,
                  "manifests": [
                    {
                      "mediaType": "application/vnd.oci.image.manifest.v1+json",
                      "size": %d,
                      "digest": "sha256:%s",
                      "annotations": {
                        "org.opencontainers.image.ref.name": "%s"
                      }
                    }
                  ]
                }
                """.formatted(manifestBytes.length, manifestDigest, refName);
        Files.writeString(ociDir.resolve("index.json"), index);

        // Tar archive for podman load
        Path archive = Files.createTempFile("oci-archive", ".tar");
        run(List.of("tar", "-cf", archive.toString(), "-C", ociDir.toString(), "."));

        PodmanRuntimeAdapter podman = new PodmanRuntimeAdapter();
        podman.importImage(archive);

        // Verify image present in podman
        Process p = new ProcessBuilder("podman", "images", "--format", "{{.Repository}}:{{.Tag}}")
                .redirectErrorStream(true)
                .start();
        String images = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        assertEquals(0, code, "podman images failed: " + images);
        boolean found = images.contains("alpine:edge")
                || images.contains("docker.io/library/alpine:edge")
                || images.contains(refName);
        assertTrue(found, "Expected alpine:edge in podman images, got: " + images);
    }

    private Path downloadBlob(BlobService blobService,
                              RegistryEndpoint endpoint,
                              String digest,
                              long size,
                              String mediaType,
                              Path ociDir) {
        try {
            Path out = ociDir.resolve("blobs").resolve("sha256").resolve(digest.replace("sha256:", ""));
            Files.createDirectories(out.getParent());
            BlobRequest req = new BlobRequest(REPO, digest, size, mediaType);
            blobService.fetchBlob(endpoint, req, out.toFile(), "repository:%s:pull".formatted(REPO));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed: " + cmd + " -> " + code + " output: " + out);
        }
    }

    private static void runIgnoreErrors(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ignored) {
            // ignore cleanup failures
        }
    }
}

