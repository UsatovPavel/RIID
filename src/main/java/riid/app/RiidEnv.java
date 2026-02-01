package riid.app;

import java.util.Map;

final class RiidEnv {
    private static volatile Map<String, String> ENV_OVERRIDE = Map.of();
    private static volatile boolean OVERRIDE_ENABLED;

    private RiidEnv() {
    }

    @SuppressWarnings("unused")
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
    @SuppressWarnings("unused")
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
}
