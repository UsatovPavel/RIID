package riid.app.daemon.guard;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.Optional;
import java.util.concurrent.Semaphore;

final public class SemaphorePullConcurrencyGuard implements PullConcurrencyGuard {
    private final Semaphore semaphore;
    private final int maxConcurrentPulls;

    public SemaphorePullConcurrencyGuard(Semaphore semaphore) {
        this(semaphore, semaphore.availablePermits());
    }

    public SemaphorePullConcurrencyGuard(Semaphore semaphore, int maxConcurrentPulls) {
        this.semaphore = Objects.requireNonNull(semaphore, "semaphore");
        if (maxConcurrentPulls < 0) {
            throw new IllegalArgumentException("maxConcurrentPulls must be >= 0");
        }
        this.maxConcurrentPulls = maxConcurrentPulls;
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

    @Override
    public boolean tryExecuteWhenIdle(IdleTask task) throws Exception {
        if (!semaphore.tryAcquire(maxConcurrentPulls)) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            semaphore.release(maxConcurrentPulls);
        }
    }
}
