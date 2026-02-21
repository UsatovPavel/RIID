package riid.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.core.fs.HostFilesystem;
import riid.core.fs.PathSupport;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.RegistryEndpoint;
import riid.core.model.manifest.RegistryApi;
import riid.core.hash.Sha256Utils;
import riid.runtime.BoundedCommandExecution;

/**
 * P2P executor backed by Dragonfly dfget CLI. Fetch-only: returns path to temp file.
 * Dispatcher is responsible for cache.put().
 */
public final class DragonflyP2PExecutor implements P2PExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DragonflyP2PExecutor.class);
    private static final String DEFAULT_DFGET = "dfget";

    private final RegistryEndpoint endpoint;
    private final HostFilesystem fs;
    private final DragonflyConfig config;

    public DragonflyP2PExecutor(RegistryEndpoint endpoint,
                                HostFilesystem fs,
                                DragonflyConfig config) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.fs = Objects.requireNonNull(fs, "fs");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType)
            throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        if (!config.enabledOrDefault()) {
            return Optional.empty();
        }
        Path tempPath = createTempFile();
        String url = endpoint.uri(RegistryApi.blobPath(repository, digest.toString())).toString();
        List<String> cmd = buildDfgetCommand(url, tempPath);
        try {
            LOGGER.info("P2P dfget start: url={}, target={}, cmd={}", url, tempPath, cmd);
            var result = BoundedCommandExecution.run(cmd);
            if (result.exitCode() != 0) {
                String msg = "dfget failed (exit " + result.exitCode() + "): "
                        + result.stdout() + result.stderr();
                throw new IOException(msg);
            }
            long actualSize = tempPath.toFile().length();
            if (size > 0 && actualSize > 0 && actualSize != size) {
                throw new IOException(
                        "P2P size mismatch for " + digest + ": expected " + size + ", got " + actualSize
                                + ". dfget stdout=" + result.stdout() + " stderr=" + result.stderr());
            }
            String computed = computeSha256(tempPath);
            if (!computed.equals(digest.toString())) {
                LOGGER.warn("P2P digest mismatch for {}: got {} (size={}, stdout={}, stderr={})",
                        digest, computed, actualSize, result.stdout(), result.stderr());
                throw new IOException(
                        "P2P digest mismatch for " + digest + ": got " + computed
                                + ". size=" + actualSize
                                + ". dfget stdout=" + result.stdout() + " stderr=" + result.stderr());
            }
            return Optional.of(tempPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("P2P dfget interrupted for " + digest, e);
        }
    }

    @Override
    public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
        // Not supported by dfget CLI; leaving no-op for now.
    }

    private List<String> buildDfgetCommand(String url, Path targetPath) {
        List<String> cmd = new ArrayList<>();
        String bin = config.dfgetPath() != null && !config.dfgetPath().isBlank()
                ? config.dfgetPath()
                : DEFAULT_DFGET;
        cmd.add(bin);
        cmd.add("--url");
        cmd.add(url);
        cmd.add("-O");
        cmd.add(targetPath.toAbsolutePath().toString());
        cmd.add("--console");
        return cmd;
    }

    private Path createTempFile() throws IOException {
        Path path = PathSupport.temporaryPath("p2p-", ".bin");
        fs.createFile(path);
        return path;
    }

    private String computeSha256(Path path) throws IOException {
        try (InputStream input = fs.newInputStream(path)) {
            return Sha256Utils.digest(input);
        } catch (IllegalStateException e) {
            throw new IOException("SHA-256 digest not available", e);
        }
    }
}
