package riid.runtime.adapter;

/**
 * Identifiers for the container runtimes RIID can import images into, each with
 * the binary RIID drives it through - the two differ for Porto
 * ({@code portoctl}) and containerd ({@code ctr}).
 */
public enum RuntimeId {
    PODMAN("podman", "podman"), PORTO("porto", "portoctl"), DOCKER("docker", "docker"), CONTAINERD("containerd", "ctr");

    private final String rawValue;
    private final String binary;

    RuntimeId(String rawValue, String binary) {
        this.rawValue = rawValue;
        this.binary = binary;
    }

    public String value() {
        return rawValue;
    }

    /**
     * Default name of the command-line tool for this runtime; a non-default install
     * overrides it through the adapter's constructor.
     */
    public String bin() {
        return binary;
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
