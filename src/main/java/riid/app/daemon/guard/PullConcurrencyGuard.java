package riid.app.daemon.guard;

import java.util.concurrent.Callable;
import java.util.Optional;

public interface PullConcurrencyGuard {
    <T> Optional<T> tryExecute(Callable<T> task) throws Exception;

    boolean tryExecuteWhenIdle(IdleTask task) throws Exception;

    @FunctionalInterface
    interface IdleTask {
        void run() throws Exception;
    }
}
