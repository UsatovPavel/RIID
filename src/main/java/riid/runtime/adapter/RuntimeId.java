package riid.runtime.adapter;

/**
 * Identifiers for the container runtimes RIID can import images into.
 */
public enum RuntimeId {
    PODMAN("podman"), PORTO("porto"), DOCKER("docker"), CONTAINERD("containerd");

    private final String rawValue;

    RuntimeId(String rawValue) {
        this.rawValue = rawValue;
    }

    public String value() {
        return rawValue;
    }

    public static RuntimeId from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("runtimeId is blank");
        }
        for (RuntimeId id : values()) {
            if (id.rawValue.equalsIgnoreCase(raw)) {
                return id;
            }
        }
        throw new IllegalArgumentException("Unsupported runtime id: " + raw);
    }

    @Override
    public String toString() {
        return rawValue;
    }
}
