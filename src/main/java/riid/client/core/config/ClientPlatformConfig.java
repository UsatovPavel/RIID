package riid.client.core.config;

import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Target OS/architecture when resolving a multi-arch manifest list (OCI index /
 * Docker manifest list).
 * <p>
 * Omitted YAML fields are filled from the JVM host via
 * {@link #withHostFallback()} on {@link ClientConfig#platformOrHostDefault()}.
 */
public record ClientPlatformConfig(@JsonProperty("os") String os, @JsonProperty("architecture") String architecture) {
    public static ClientPlatformConfig fromHost() {
        return new ClientPlatformConfig(hostOs(), hostArchitecture());
    }

    /**
     * Applies non-blank YAML fields; any blank component is taken from
     * {@link #fromHost()}.
     */
    public ClientPlatformConfig withHostFallback() {
        ClientPlatformConfig host = fromHost();
        String o = blank(os) ? host.os() : os.trim().toLowerCase(Locale.ROOT);
        String a = blank(architecture) ? host.architecture() : normalizeArchitecture(architecture.trim());
        return new ClientPlatformConfig(o, a);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String hostOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("linux")) {
            return "linux";
        }
        if (name.contains("windows")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "darwin";
        }
        return name.isEmpty() ? "linux" : name;
    }

    private static String hostArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return normalizeArchitecture(arch);
    }

    static String normalizeArchitecture(String arch) {
        String a = Objects.requireNonNull(arch, "arch").toLowerCase(Locale.ROOT);
        return switch (a) {
            case "x86_64", "x86-64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> a;
        };
    }
}
