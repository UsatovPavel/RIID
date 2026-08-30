package riid.runtime.adapter;

import java.util.Optional;

import riid.core.fs.NioHostFilesystem;

/** Deterministic Podman CLI fallback for adapter tests. */
public abstract class CliOnlyPodmanRuntimeAdapter extends PodmanRuntimeAdapter {
    protected CliOnlyPodmanRuntimeAdapter(boolean prefixImport) {
        super(new NioHostFilesystem(), prefixImport, Optional.empty());
    }
}
