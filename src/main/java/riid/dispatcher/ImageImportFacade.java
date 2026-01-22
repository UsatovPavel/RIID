package riid.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.runtime.RuntimeAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Connects RequestDispatcher (download/validate) with a RuntimeAdapter (import).
 * 7.0/7.1/7.2 from Plan PR 3: fetch -> validate -> pass to runtime, with clear errors.
 */
public final class ImageImportFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageImportFacade.class);

    private final RequestDispatcher dispatcher;

    public ImageImportFacade(RequestDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
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

        Path imagePath = Path.of(result.path());
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
        if (isBlank(result.digest())) {
            throw new DispatcherRuntimeException("Missing digest from dispatcher result");
        }
        if (isBlank(result.mediaType())) {
            throw new DispatcherRuntimeException("Missing media type from dispatcher result");
        }
        if (isBlank(result.path())) {
            throw new DispatcherRuntimeException("Missing path from dispatcher result");
        }
        Path p = Path.of(result.path());
        if (!Files.exists(p)) {
            throw new DispatcherRuntimeException("Fetched path does not exist: " + p);
        }
        if (!Files.isRegularFile(p)) {
            throw new DispatcherRuntimeException("Fetched path is not a regular file: " + p);
        }
        try {
            long size = Files.size(p);
            if (size <= 0) {
                throw new DispatcherRuntimeException("Fetched file is empty: " + p);
            }
        } catch (IOException e) {
            throw new DispatcherRuntimeException("Cannot read fetched file: " + p, e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}


