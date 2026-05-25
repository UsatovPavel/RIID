package riid.app.daemon;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

import riid.app.cli.CliApplication;

/**
 * Owns daemon-lifetime resources and exposes a request-level loader.
 */
public final class DaemonRuntimeContext implements AutoCloseable {
    private final CliApplication.ImageLoader imageLoader;
    private final List<AutoCloseable> resourceOwners;

    public DaemonRuntimeContext(CliApplication.ImageLoader imageLoader, AutoCloseable resourceOwner) {
        this(imageLoader, resourceOwner == null ? List.of() : List.of(resourceOwner));
    }

    public DaemonRuntimeContext(CliApplication.ImageLoader imageLoader, List<AutoCloseable> resourceOwners) {
        this.imageLoader = Objects.requireNonNull(imageLoader, "imageLoader");
        Objects.requireNonNull(resourceOwners, "resourceOwners");
        List<AutoCloseable> nonNullOwners = new ArrayList<>(resourceOwners.size());
        for (AutoCloseable owner : resourceOwners) {
            if (owner != null) {
                nonNullOwners.add(owner);
            }
        }
        this.resourceOwners = List.copyOf(nonNullOwners);
    }

    public CliApplication.ImageLoader imageLoader() {
        return imageLoader;
    }

    @Override
    public void close() throws Exception {
        Exception error = null;
        // Close in reverse registration order to mirror stack-like bootstrap.
        for (int i = resourceOwners.size() - 1; i >= 0; i--) {
            try {
                resourceOwners.get(i).close();
            } catch (Exception e) {
                if (error == null) {
                    error = e;
                } else {
                    error.addSuppressed(e);
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }
}
