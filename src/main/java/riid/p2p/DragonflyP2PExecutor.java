package riid.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.app.fs.HostFilesystem;
import riid.app.fs.PathSupport;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheEntry;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.ValidationException;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.RegistryApi;
import riid.runtime.BoundedCommandExecution;

/**
 * P2P executor backed by Dragonfly dfget CLI.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "CacheAdapter is a shared collaborator, not exposed mutably")
public final class DragonflyP2PExecutor implements P2PExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DragonflyP2PExecutor.class);
    private static final String DEFAULT_DFGET = "dfget";

    private final RegistryEndpoint endpoint;
    private final CacheAdapter cache;
    private final HostFilesystem fs;
    private final DragonflyConfig config;

    public DragonflyP2PExecutor(RegistryEndpoint endpoint,
                                CacheAdapter cache,
                                HostFilesystem fs,
                                DragonflyConfig config) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.cache = cache;
        this.fs = Objects.requireNonNull(fs, "fs");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType) {
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
                throw new ValidationException(
                        "P2P size mismatch for " + digest + ": expected " + size + ", got " + actualSize
                                + ". dfget stdout=" + result.stdout() + " stderr=" + result.stderr());
            }
            String computed = computeSha256(tempPath);
            if (!computed.equals(digest.toString())) {
                LOGGER.warn("P2P digest mismatch for {}: got {} (size={}, stdout={}, stderr={})",
                        digest, computed, actualSize, result.stdout(), result.stderr());
                throw new ValidationException(
                        "P2P digest mismatch for " + digest + ": got " + computed
                                + ". size=" + actualSize
                                + ". dfget stdout=" + result.stdout() + " stderr=" + result.stderr());
            }
            return Optional.of(cacheOrTemp(tempPath, digest, mediaType, actualSize));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("P2P dfget interrupted for " + digest, e);
        } catch (ValidationException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("P2P dfget failed for " + digest, e);
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

    private Path createTempFile() {
        try {
            Path path = PathSupport.temporaryPath("p2p-", ".bin");
            fs.createFile(path);
            return path;
        } catch (IOException e) {
            throw new RuntimeException("Cannot create temp file for dfget", e);
        }
    }

    private Path cacheOrTemp(Path tempPath, ImageDigest digest, CacheMediaType mediaType, long size) {
        if (cache == null) {
            return tempPath;
        }
        try {
            CacheEntry entry = cache.put(digest,
                    FilesystemCachePayload.of(fs, tempPath, size),
                    mediaType);
            Path resolved = cache.resolve(entry.key()).orElse(tempPath);
            if (!resolved.equals(tempPath)) {
                try {
                    fs.deleteIfExists(tempPath);
                } catch (IOException ex) {
                    LOGGER.warn("Failed to delete temp p2p file {}: {}", tempPath, ex.getMessage());
                }
            }
            return resolved;
        } catch (ValidationException ve) {
            LOGGER.warn("Validation error for cache put ({}): {}", mediaType, ve.getMessage());
        } catch (IllegalArgumentException iae) {
            LOGGER.warn("Unsupported media type for cache put ({}): {}", mediaType, iae.getMessage());
        } catch (IOException ex) {
            LOGGER.warn("Failed to put layer {} to cache: {}", digest, ex.getMessage());
        }
        return tempPath;
    }

    private String computeSha256(Path path) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 digest not available", e);
        }
        try (InputStream input = fs.newInputStream(path);
             DigestInputStream digestStream = new DigestInputStream(input, md)) {
            byte[] buffer = new byte[8192];
            while (digestStream.read(buffer) != -1) {
                // digest updated by stream
            }
        }
        return "sha256:" + bytesToHex(md.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
