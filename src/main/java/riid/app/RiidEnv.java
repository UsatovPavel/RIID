package riid.app;
import java.util.Map;

import riid.dispatcher.ImageRef;

final class RiidEnv {
    private static volatile Map<String, String> envOverride;

    private RiidEnv() { }

    static ImageRef imageRef() {
        Map<String, String> env = env();
        String repo = env.getOrDefault("RIID_REPO", "library/busybox");
        String tag = env.get("RIID_TAG");
        String digest = env.get("RIID_DIGEST");
        String ref = env.getOrDefault("RIID_REF", "latest");

        if (digest == null || digest.isBlank()) {
            if (ref.startsWith("sha256:")) {
                digest = ref;
            } else if (tag == null || tag.isBlank()) {
                tag = ref;
            }
        }
        if (tag != null && tag.isBlank()) {
            tag = null;
        }
        return new ImageRef(repo, tag, digest);
    }

    static String cacheDir() {
        String v = env().get("RIID_CACHE_DIR");
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("RIID_CACHE_DIR is not set");
        }
        return v;
    }

    /**
     * @VisibleForTesting
     */
    static void setEnvForTests(Map<String, String> env) {
        if (env == null) {
            envOverride = null;
            return;
        }
        Map<String, String> sanitized = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        envOverride = Map.copyOf(sanitized);
    }

    private static Map<String, String> env() {
        if (envOverride != null) {
            return envOverride;
        }
        return System.getenv();
    }
}
