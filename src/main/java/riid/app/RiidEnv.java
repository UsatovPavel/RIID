package riid.app;
import java.util.Map;

final class RiidEnv {
    private static volatile Map<String, String> envOverride;

    private RiidEnv() { }

    static String repo() {
        return env().getOrDefault("RIID_REPO", "library/busybox");
    }

    static String tag() {
        java.util.Map<String, String> env = env();
        return env.getOrDefault("RIID_TAG", env.getOrDefault("RIID_REF", "latest"));
    }

    static String digest() {
        Map<String, String> env = env();
        String v = env.get("RIID_DIGEST");
        if (v != null && !v.isBlank()) {
            return v;
        }
        String ref = env.get("RIID_REF");
        if (ref != null && ref.startsWith("sha256:")) {
            return ref;
        }
        return null;
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
