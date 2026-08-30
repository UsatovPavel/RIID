package riid.runtime.adapter;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Name an image is to get inside a container engine: repository plus tag, never
 * a registry host - an imported image is local. Derived from the app layer's
 * ImageId at the boundary, the way the dispatcher derives its own ImageRef.
 */
public record ImageReference(String repository, String tag) {

    public ImageReference {
        Objects.requireNonNull(repository, "repository");
        if (repository.isBlank()) {
            throw new IllegalArgumentException("repository is blank");
        }
    }

    /**
     * Reference as an engine that keeps the name as given sees it (containerd,
     * Porto): {@code repository[:tag]}.
     */
    public String name() {
        return tag == null || tag.isBlank() ? repository : repository + ":" + tag;
    }

    /**
     * Reference for an engine that qualifies an unqualified name itself: podman
     * would send {@code library/app} to Docker Hub, {@code localhost/library/app}
     * is what its own {@code load} produces.
     */
    public String localName() {
        return "localhost/" + name();
    }

    @Override
    public @NonNull String toString() {
        return name();
    }
}
