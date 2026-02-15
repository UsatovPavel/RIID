package riid.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.PathSupport;

/**
 * Docker adapter: accepts OCI archive, rewrites to docker-save format, feeds to `docker load`.
 */
public class DockerRuntimeAdapter implements RuntimeAdapter {
    private static final String RUNTIME_ID = "docker";
    private static final String DEFAULT_DOCKER_BIN = "docker";
    private static final String DIGEST_FIELD = "digest";
    private final HostFilesystem fs;
    private final Path tempRoot;
    private final String dockerCmd;

    public DockerRuntimeAdapter() {
        this(new NioHostFilesystem(), null, DEFAULT_DOCKER_BIN);
    }

    public DockerRuntimeAdapter(HostFilesystem fs) {
        this(fs, null, DEFAULT_DOCKER_BIN);
    }

    public DockerRuntimeAdapter(HostFilesystem fs, Path tempRoot) {
        this(fs, tempRoot, DEFAULT_DOCKER_BIN);
    }

    public DockerRuntimeAdapter(HostFilesystem fs, Path tempRoot, String dockerCmd) {
        this.fs = fs != null ? fs : new NioHostFilesystem();
        this.tempRoot = tempRoot;
        this.dockerCmd = normalizeDockerCmd(dockerCmd);
    }

    @Override
    public String runtimeId() {
        return RUNTIME_ID;
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!fs.exists(imagePath) || !fs.isRegularFile(imagePath)) {
            throw new IOException("Image file not found: " + imagePath);
        }

        Path workDir = PathSupport.tempDirPath(tempRoot, "docker-import-oci-");
        fs.createDirectory(workDir);
        // unpack OCI archive
        untar(imagePath, workDir);

        // read index and manifest
        ObjectMapper mapper = new ObjectMapper();
        JsonNode index;
        try (InputStream in = fs.newInputStream(workDir.resolve("index.json"))) {
            index = mapper.readTree(in);
        }
        JsonNode manifestNode = index.path("manifests").get(0);
        if (manifestNode == null || manifestNode.isMissingNode()) {
            throw new IOException("OCI archive missing manifests");
        }
        String manifestDigest = stripSha256(manifestNode.path(DIGEST_FIELD).asText(""));
        if (manifestDigest.isBlank()) {
            throw new IOException("OCI archive manifest digest missing");
        }
        String refName = manifestNode.path("annotations").path("org.opencontainers.image.ref.name").asText(null);
        if (refName == null || refName.isBlank()) {
            refName = manifestNode.path("annotations").path("io.containerd.image.name").asText(null);
        }
        if (refName == null || refName.isBlank()) {
            refName = "docker.io/library/unknown:latest";
        }

        JsonNode manifest;
        try (InputStream in = fs.newInputStream(workDir.resolve("blobs").resolve("sha256").resolve(manifestDigest))) {
            manifest = mapper.readTree(in);
        }

        // compose docker save manifest.json
        writeDockerManifestJson(workDir, manifest, refName, mapper);
        writeDockerRepositories(workDir, refName, manifest, mapper);

        Path dockerArchive = PathSupport.temporaryPath(tempRoot, "docker-load-", ".tar");
        fs.createFile(dockerArchive);
        tar(workDir, dockerArchive);

        runDockerLoad(dockerArchive);
    }

    private void writeDockerManifestJson(Path workDir,
                                         JsonNode manifest,
                                         String refName,
                                         ObjectMapper mapper) throws IOException {
        String configPath = "blobs/sha256/" + stripSha256(
                manifest.path("config").path(DIGEST_FIELD).asText(""));

        List<String> layers = new ArrayList<>();
        Map<String, Object> layerSources = new LinkedHashMap<>();
        for (JsonNode layer : manifest.path("layers")) {
            String digest = layer.path(DIGEST_FIELD).asText("");
            String hex = stripSha256(digest);
            layers.add("blobs/sha256/" + hex);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mediaType", layer.path("mediaType").asText(""));
            meta.put("size", layer.path("size").asLong());
            meta.put(DIGEST_FIELD, digest);
            layerSources.put(digest, meta);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Config", configPath);
        entry.put("RepoTags", List.of(refName));
        entry.put("Layers", layers);
        entry.put("LayerSources", layerSources);

        try (var out = fs.newOutputStream(workDir.resolve("manifest.json"))) {
            mapper.writeValue(out, List.of(entry));
        }
    }

    private void writeDockerRepositories(Path workDir,
                                         String refName,
                                         JsonNode manifest,
                                         ObjectMapper mapper) throws IOException {
        int sep = refName.lastIndexOf(':');
        String repoKey = sep > 0 ? refName.substring(0, sep) : refName;
        String tag = sep > 0 ? refName.substring(sep + 1) : "latest";
        JsonNode firstLayer = manifest.path("layers").get(0);
        String topLayer = stripSha256(firstLayer.path(DIGEST_FIELD).asText(""));

        Map<String, Map<String, String>> repositories = new LinkedHashMap<>();
        repositories.put(repoKey, Map.of(tag, topLayer));
        try (var out = fs.newOutputStream(workDir.resolve("repositories"))) {
            mapper.writeValue(out, repositories);
        }
    }

    protected void untar(Path archive, Path destDir) throws IOException, InterruptedException {
        List<String> cmd = List.of("tar", "-xf", archive.toString(), "-C", destDir.toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("Failed to unpack OCI archive (exit " + shellResult.exitCode() + "): "
                    + shellResult.stdout() + shellResult.stderr());
        }
    }

    protected void tar(Path sourceDir, Path destTar) throws IOException, InterruptedException {
        List<String> cmd = List.of("tar", "-cf", destTar.toString(), "-C", sourceDir.toString(), ".");
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("Failed to create docker archive (exit " + shellResult.exitCode() + "): "
                    + shellResult.stdout() + shellResult.stderr());
        }
    }

    private void runDockerLoad(Path dockerArchive) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                dockerCmd,
                "load",
                "-q",
                "-i",
                dockerArchive.toAbsolutePath().toString()
        );
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("docker load failed (exit " + shellResult.exitCode() + "): "
                    + shellResult.stdout() + shellResult.stderr());
        }
    }

    private static String stripSha256(String digest) {
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    private static String normalizeDockerCmd(String value) {
        return value == null || value.isBlank() ? DEFAULT_DOCKER_BIN : value;
    }

    protected BoundedCommandExecution.ShellResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        return BoundedCommandExecution.run(command);
    }
}
