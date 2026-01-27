package riid.app.ociarchive;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import riid.app.ImageId;
import riid.app.error.AppError;
import riid.app.error.OciArchiveException;
import riid.app.fs.HostFilesystem;
import riid.cache.oci.ImageDigest;
import riid.app.fs.PathSupport;
import riid.client.api.ManifestResult;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.MediaType;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.model.RepositoryName;

/**
 * Builds an OCI archive from a manifest, pulling blobs via RequestDispatcher.
 */
public final class OciArchiveBuilder {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RequestDispatcher dispatcher;
    private final HostFilesystem fs;
    private final Path tempRoot;

    public OciArchiveBuilder(RequestDispatcher dispatcher, HostFilesystem fs) {
        this(dispatcher, fs, null);
    }

    public OciArchiveBuilder(RequestDispatcher dispatcher, HostFilesystem fs, Path tempRoot) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.fs = Objects.requireNonNull(fs, "fs");
        this.tempRoot = tempRoot;
    }

    public <T> T withArchive(ImageId imageId,
                             ManifestResult manifestResult,
                             ArchiveUser<T> user) throws IOException, InterruptedException {
        try (OciArchive archive = build(imageId, manifestResult)) {
            return user.use(archive.archivePath());
        }
    }

    private OciArchive build(ImageId imageId, ManifestResult manifestResult)
            throws IOException, InterruptedException {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(manifestResult, "manifestResult");

        Manifest manifest = manifestResult.manifest();
        Path ociDir = PathSupport.tempDirPath(tempRoot, "oci-layout-");
        fs.createDirectory(ociDir);
        Path blobsDir = ociDir.resolve("blobs").resolve("sha256");
        fs.createDirectory(blobsDir);

        // Config
        var cfg = manifest.config();
        pullLayer(imageId.name(),
                ImageDigest.parse(cfg.digest()),
                cfg.size(),
                MediaType.from(cfg.mediaType()),
                blobsDir);

        // Layers
        for (var layer : manifest.layers()) {
            pullLayer(imageId.name(),
                    ImageDigest.parse(layer.digest()),
                    layer.size(),
                    MediaType.from(layer.mediaType()),
                    blobsDir);
        }

        // Manifest blob
        byte[] manifestBytes = OBJECT_MAPPER.writeValueAsBytes(manifest);
        String manifestDigest = manifestResult.digest().replace("sha256:", "");
        fs.write(blobsDir.resolve(manifestDigest), manifestBytes);

        // oci-layout
        fs.writeString(ociDir.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");

        // index.json with ref name
        String template = readResource("oci/index/json.tpl");
        String index = String.format(Locale.ROOT, template, manifestBytes.length, 
            manifestDigest, imageId.referenceName());
        fs.writeString(ociDir.resolve("index.json"), index);

        Path archive = PathSupport.tempPath(tempRoot, "oci-archive-", ".tar");
        fs.createFile(archive);
        runTar(archive, ociDir);
        return new OciArchive(archive, ociDir, fs);
    }

    @FunctionalInterface
    public interface ArchiveUser<T> {
        T use(Path archivePath) throws IOException, InterruptedException;
    }

    private void pullLayer(String repository,
                           ImageDigest digest,
                           long size,
                           MediaType mediaType,
                           Path blobsDir) throws IOException {
        var fetched = dispatcher.fetchLayer(new RepositoryName(repository), digest, size, mediaType);
        File tmp = fetched.path().toFile();
        fs.copy(tmp.toPath(), blobsDir.resolve(fetched.digest().hex()));
    }

    private static void runTar(Path archive, Path ociDir) throws IOException, InterruptedException {
        // tar -c: create archive, -f: output file, -C: change dir before adding "."
        Process p = new ProcessBuilder("tar", "-cf", archive.toString(), "-C", ociDir.toString(), ".")
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar failed with exit " + code);
        }
    }
    
    private static String readResource(String path) throws OciArchiveException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (var in = loader.getResourceAsStream(path)) {
            if (in == null) {
                String msg = AppError.OciErrorKind.RESOURCE_NOT_FOUND.format(path);
                throw new OciArchiveException(
                        new AppError.Oci(AppError.OciErrorKind.RESOURCE_NOT_FOUND, msg),
                        msg);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            String msg = AppError.OciErrorKind.RESOURCE_READ_FAILED.format(path);
            throw new OciArchiveException(
                    new AppError.Oci(AppError.OciErrorKind.RESOURCE_READ_FAILED, msg),
                    msg, e);
        }
    }
}

