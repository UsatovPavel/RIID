package riid.app;

final class RiidEnv {
    private RiidEnv() { }

    static String repo() {
        return System.getenv().getOrDefault("RIID_REPO", "library/busybox");
    }

    static String tag() {
        return System.getenv().getOrDefault("RIID_TAG", System.getenv().getOrDefault("RIID_REF", "latest"));
    }

    static String digest() {
        String v = System.getenv("RIID_DIGEST");
        if (v != null && !v.isBlank()) {
            return v;
        }
        String ref = System.getenv("RIID_REF");
        if (ref != null && ref.startsWith("sha256:")) {
            return ref;
        }
        return null;
    }

    static String cacheDir() {
        return System.getenv("RIID_CACHE_DIR");
    }
}
