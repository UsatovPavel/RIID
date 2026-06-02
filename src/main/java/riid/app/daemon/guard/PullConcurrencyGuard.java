package riid.app.daemon.guard;

import java.util.concurrent.Callable;
import java.util.Optional;

public interface PullConcurrencyGuard {
    <T> Optional<T> tryExecute(Callable<T> task) throws Exception;
}
