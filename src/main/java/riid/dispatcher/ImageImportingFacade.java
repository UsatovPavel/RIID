package riid.dispatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.dispatcher.model.FetchResult;
import riid.dispatcher.model.ImageRef;
import riid.runtime.RuntimeAdapter;

/**
 * Connects RequestDispatcher (download/validate) with a RuntimeAdapter (import).
 * 7.0/7.1/7.2 from Plan PR 3: fetch -> validate -> pass to runtime, with clear errors.
 */
public final class ImageImportingFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageImportingFacade.class);

    private final RequestDispatcher dispatcher;
    private final HostFilesystem fs;

    public ImageImportingFacade(RequestDispatcher dispatcher) {
        this(dispatcher, new NioHostFilesystem());
    }

    public ImageImportingFacade(RequestDispatcher dispatcher, HostFilesystem fs) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    /**
     * Fetches image layers via dispatcher, validates the result, then imports into the given runtime.
     *
     * @return FetchResult that was handed to the runtime.
     * @throws DispatcherRuntimeException when validation or runtime import fails.
     */
    public FetchResult fetchAndLoad(ImageRef ref, RuntimeAdapter runtime) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(runtime, "runtime");

        FetchResult result = dispatcher.fetchImage(ref);
        validateResult(result);

        Path imagePath = result.path();
        try {
            LOGGER.info("Importing digest {} into runtime {}", result.digest(), runtime.runtimeId());
            runtime.importImage(imagePath);
            return result;
        } catch (IOException e) {
            throw new DispatcherRuntimeException(
                    "Failed to import image into runtime " + runtime.runtimeId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DispatcherRuntimeException(
                    "Interrupted while importing image into runtime " + runtime.runtimeId(), e);
        }
    }

    private void validateResult(FetchResult result) {
        if (result == null) {
            throw new DispatcherRuntimeException("Dispatcher returned null result");
        }
        if (result.digest() == null) {
            throw new DispatcherRuntimeException("Missing digest from dispatcher result");
        }
        if (result.mediaType() == null) {
            throw new DispatcherRuntimeException("Missing media type from dispatcher result");
        }
        if (result.path() == null) {
            throw new DispatcherRuntimeException("Missing path from dispatcher result");
        }
        Path p = result.path();
        if (!fs.exists(p)) {
            throw new DispatcherRuntimeException("Fetched path does not exist: " + p);
        }
        if (!fs.isRegularFile(p)) {
            throw new DispatcherRuntimeException("Fetched path is not a regular file: " + p);
        }
        try {
            long size = fs.size(p);
            if (size <= 0) {
                throw new DispatcherRuntimeException("Fetched file is empty: " + p);
            }
        } catch (IOException e) {
            throw new DispatcherRuntimeException("Cannot read fetched file: " + p, e);
        }
    }

}


