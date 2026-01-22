package riid.app;

import riid.client.core.config.RegistryEndpoint;

/**
 * Full image identity: registry + name + tag + digest.
 */
public record ImageId(String registry, String name, String tag, String digest) {
    public ImageId {
        if (registry == null || registry.isBlank()) {
            throw new IllegalArgumentException("registry is blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        if ((tag == null || tag.isBlank()) && (digest == null || digest.isBlank())) {
            throw new IllegalArgumentException("tag or digest must be provided");
        }
    }

    public ImageId withDigest(String newDigest) {
        if (newDigest == null || newDigest.isBlank()) {
            return this;
        }
        return new ImageId(registry, name, tag, newDigest);
    }

    public static ImageId fromRegistry(String registry, String name, String reference) {
        if (reference != null && reference.startsWith("sha256:")) {
            return new ImageId(registry, name, null, reference);
        }
        return new ImageId(registry, name, reference, null);
    }

    public static String registryFor(RegistryEndpoint endpoint) {
        int port = endpoint.port();
        if (port > 0) {
            return endpoint.host() + ":" + port;
        }
        return endpoint.host();
    }

    /**
     * Reference name for OCI index.json annotations (name[:tag]).
     */
    public String refName() {
        if (tag != null && !tag.isBlank()) {
            return name + ":" + tag;
        }
        return name;
    }

    /**
     * Reference for registry API calls: use tag if present, otherwise digest.
     */
    public String reference() {
        if (tag != null && !tag.isBlank()) {
            return tag;
        }
        return digest;
    }

    @Override
    public String toString() {
        String base = registry + "/" + name;
        if (tag != null && !tag.isBlank()) {
            base = base + ":" + tag;
        }
        if (digest != null && !digest.isBlank()) {
            base = base + "@" + digest;
        }
        return base;
    }
}

