package riid.app;

import java.util.Map;

import riid.dispatcher.ImageRef;

final class RiidEnv {
    private static volatile Map<String, String> ENV_OVERRIDE = Map.of();
    private static volatile boolean OVERRIDE_ENABLED;

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
        String tagValue = normalizeTag(tag);
        return new ImageRef(repo, tagValue, digest);
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
            OVERRIDE_ENABLED = false;
            ENV_OVERRIDE = Map.of();
            return;
        }
        Map<String, String> sanitized = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        ENV_OVERRIDE = Map.copyOf(sanitized);
        OVERRIDE_ENABLED = true;
    }

    private static Map<String, String> env() {
        if (OVERRIDE_ENABLED) {
            return ENV_OVERRIDE;
        }
        return System.getenv();
    }

    private static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        return tag;
    }
}
