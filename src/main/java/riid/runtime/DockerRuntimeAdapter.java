package riid.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Docker adapter: accepts OCI archive, rewrites to docker-save format, feeds to `docker load`.
 */
public class DockerRuntimeAdapter implements RuntimeAdapter {
    private static final String DOCKER_BIN = "docker";

    @Override
    public String runtimeId() {
        return DOCKER_BIN;
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        Path workDir = Files.createTempDirectory("docker-import-oci");
        // unpack OCI archive
        untar(imagePath, workDir);

        // read index and manifest
        ObjectMapper mapper = new ObjectMapper();
        JsonNode index = mapper.readTree(workDir.resolve("index.json").toFile());
        JsonNode manifestNode = index.path("manifests").get(0);
        if (manifestNode == null || manifestNode.isMissingNode()) {
            throw new IOException("OCI archive missing manifests");
        }
        String manifestDigest = stripSha256(manifestNode.path("digest").asText(""));
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

        JsonNode manifest = mapper.readTree(
                workDir.resolve("blobs").resolve("sha256").resolve(manifestDigest).toFile());

        // compose docker save manifest.json
        writeDockerManifestJson(workDir, manifest, refName, mapper);
        writeDockerRepositories(workDir, refName, manifest, mapper);

        Path dockerArchive = Files.createTempFile("docker-load", ".tar");
        tar(workDir, dockerArchive);

        runDockerLoad(dockerArchive);
    }

    private void writeDockerManifestJson(Path workDir,
                                         JsonNode manifest,
                                         String refName,
                                         ObjectMapper mapper) throws IOException {
        String configPath = "blobs/sha256/" + stripSha256(
                manifest.path("config").path("digest").asText(""));

        List<String> layers = new ArrayList<>();
        Map<String, Object> layerSources = new LinkedHashMap<>();
        for (JsonNode layer : manifest.path("layers")) {
            String digest = layer.path("digest").asText("");
            String hex = stripSha256(digest);
            layers.add("blobs/sha256/" + hex);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mediaType", layer.path("mediaType").asText(""));
            meta.put("size", layer.path("size").asLong());
            meta.put("digest", digest);
            layerSources.put(digest, meta);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Config", configPath);
        entry.put("RepoTags", List.of(refName));
        entry.put("Layers", layers);
        entry.put("LayerSources", layerSources);

        mapper.writeValue(workDir.resolve("manifest.json").toFile(), List.of(entry));
    }

    private void writeDockerRepositories(Path workDir,
                                         String refName,
                                         JsonNode manifest,
                                         ObjectMapper mapper) throws IOException {
        int sep = refName.lastIndexOf(':');
        String repoKey = sep > 0 ? refName.substring(0, sep) : refName;
        String tag = sep > 0 ? refName.substring(sep + 1) : "latest";
        JsonNode firstLayer = manifest.path("layers").get(0);
        String topLayer = stripSha256(firstLayer.path("digest").asText(""));

        Map<String, Map<String, String>> repositories = new LinkedHashMap<>();
        repositories.put(repoKey, Map.of(tag, topLayer));
        mapper.writeValue(workDir.resolve("repositories").toFile(), repositories);
    }

    protected void untar(Path archive, Path destDir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", destDir.toString())
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("Failed to unpack OCI archive: " + out);
        }
    }

    protected void tar(Path sourceDir, Path destTar) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-cf", destTar.toString(), "-C", sourceDir.toString(), ".")
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("Failed to create docker archive: " + out);
        }
    }

    private void runDockerLoad(Path dockerArchive) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                DOCKER_BIN,
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

    protected BoundedCommandExecution.ShellResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        return BoundedCommandExecution.run(command);
    }
}
