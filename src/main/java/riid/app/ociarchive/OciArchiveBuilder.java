package riid.app.ociarchive;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import riid.app.error.AppError;
import riid.app.ImageId;
import riid.app.error.OciArchiveException;
import riid.app.fs.HostFilesystem;
import riid.cache.ImageDigest;
import riid.client.api.BlobResult;
import riid.client.api.ManifestResult;
import riid.client.core.model.manifest.Manifest;
import riid.dispatcher.RequestDispatcher;

/**
 * Builds an OCI archive from a manifest, pulling blobs via RequestDispatcher.
 */
public final class OciArchiveBuilder {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RequestDispatcher dispatcher;
    private final HostFilesystem fs;

    public OciArchiveBuilder(RequestDispatcher dispatcher, HostFilesystem fs) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    public OciArchive build(ImageId imageId, ManifestResult manifestResult)
            throws IOException, InterruptedException {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(manifestResult, "manifestResult");

        Manifest manifest = manifestResult.manifest();
        Path ociDir = fs.createTempDirectory("oci-layout");
        Path blobsDir = ociDir.resolve("blobs").resolve("sha256");
        fs.createDirectories(blobsDir);

        // Config
        var cfg = manifest.config();
        pullLayer(imageId.name(), cfg.digest(), cfg.size(), cfg.mediaType(), blobsDir);

        // Layers
        for (var layer : manifest.layers()) {
            pullLayer(imageId.name(), layer.digest(), layer.size(), layer.mediaType(), blobsDir);
        }

        // Manifest blob
        byte[] manifestBytes = OBJECT_MAPPER.writeValueAsBytes(manifest);
        String manifestDigest = manifestResult.digest().replace("sha256:", "");
        fs.write(blobsDir.resolve(manifestDigest), manifestBytes);

        // oci-layout
        fs.writeString(ociDir.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");

        // index.json with ref name
        String template = readResource("oci/index/json.tpl");
        String index = String.format(Locale.ROOT, template, manifestBytes.length, manifestDigest, imageId.refName());
        fs.writeString(ociDir.resolve("index.json"), index);

        Path archive = fs.createTempFile("oci-archive", ".tar");
        runTar(archive, ociDir);
        return new OciArchive(archive, ociDir);
    }

    private void pullLayer(String repository,
                           String digest,
                           long size,
                           String mediaType,
                           Path blobsDir) throws IOException {
        var fetched = dispatcher.fetchLayer(repository, digest, size, mediaType);
        File tmp = new File(fetched.path());
        BlobResult blob = new BlobResult(fetched.digest(), tmp.length(), fetched.mediaType(), fetched.path());
        ImageDigest imgDigest = ImageDigest.parse(blob.digest());
        fs.copy(tmp.toPath(), blobsDir.resolve(imgDigest.hex()));
    }

    private static void runTar(Path archive, Path ociDir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-cf", archive.toString(), "-C", ociDir.toString(), ".")
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar failed with exit " + code);
        }
    }
    private static String readResource(String path) throws OciArchiveException {
        try (var in = OciArchiveBuilder.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new OciArchiveException(
                        new AppError.Oci(AppError.OciKind.RESOURCE_NOT_FOUND, "Resource not found: " + path),
                        "Resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OciArchiveException(
                    new AppError.Oci(AppError.OciKind.RESOURCE_READ_FAILED, "Failed to read resource: " + path),
                    "Failed to read resource: " + path, e);
        }
    }
}

