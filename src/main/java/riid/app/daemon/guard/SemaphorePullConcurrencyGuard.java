package riid.app.daemon.guard;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.Optional;
import java.util.concurrent.Semaphore;

final public class SemaphorePullConcurrencyGuard implements PullConcurrencyGuard {
    private final Semaphore semaphore;

    public SemaphorePullConcurrencyGuard(Semaphore semaphore) {
        this.semaphore = Objects.requireNonNull(semaphore, "semaphore");
    }

    @Override
    public <T> Optional<T> tryExecute(Callable<T> task) throws Exception {
        if (!semaphore.tryAcquire()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(task.call());
        } finally {
            semaphore.release();
        }
    }
}
