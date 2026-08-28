package riid.runtime.prefix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.OciLayout;
import riid.core.model.manifest.TestManifests;

/**
 * Fake but self-consistent images for prefix-import tests: the config is stored
 * under the hash of its own bytes, exactly as a registry serves it, so an
 * adapter that looks a blob up by digest finds it.
 */
public final class PrefixImportFixtures {

    private PrefixImportFixtures() {
    }

    public static Manifest manifest(int layerCount) {
        List<Descriptor> layers = new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            layers.add(TestManifests.gzipLayer(OciLayout.DIGEST_PREFIX + layerDigestHex(i), 8));
        }
        byte[] config = configJson(layerCount);
        return TestManifests.manifest(TestManifests.config(OciLayout.DIGEST_PREFIX + sha256Hex(config), config.length),
                layers);
    }

    /**
     * An OCI layout holding the layer blobs and the config, as RIID fills it in.
     */
    public static Path layoutWithBlobs(Path root, Manifest manifest) throws IOException {
        Path blobs = Files
                .createDirectories(root.resolve("oci").resolve(OciLayout.BLOBS_DIR).resolve(OciLayout.SHA256_DIR));
        for (int i = 0; i < manifest.layers().size(); i++) {
            Files.writeString(blobs.resolve(layerDigestHex(i)), "layer-" + i);
        }
        Files.write(blobs.resolve(hex(manifest.config().digest())), configJson(manifest.layers().size()));
        return blobs;
    }

    /**
     * Written by hand rather than serialized: testFixtures has no Jackson on its
     * compile path, and the shape is fixed anyway.
     */
    public static byte[] configJson(int layerCount) {
        StringJoiner diffIds = new StringJoiner(",", "[", "]");
        for (int i = 0; i < layerCount; i++) {
            diffIds.add("\"" + OciLayout.DIGEST_PREFIX + "d".repeat(63) + i + "\"");
        }
        String json = "{\"" + OciLayout.ROOTFS + "\":{\"type\":\"layers\",\"" + OciLayout.DIFF_IDS + "\":" + diffIds
                + "},\"" + OciLayout.HISTORY + "\":[{\"created_by\":\"test\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static String layerDigestHex(int index) {
        return Integer.toHexString(index + 10).repeat(64).substring(0, 64);
    }

    public static String hex(String digest) {
        return digest.substring(OciLayout.DIGEST_PREFIX.length());
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
