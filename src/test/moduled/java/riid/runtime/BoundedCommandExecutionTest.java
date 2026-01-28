package riid.runtime;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class BoundedCommandExecutionTest {

    @Test
    void setMaxOutputBytesRejectsNonPositive() {
        var ex1 = assertThrows(IllegalArgumentException.class,
                () -> BoundedCommandExecution.setMaxOutputBytes(0));
        var ex2 = assertThrows(IllegalArgumentException.class,
                () -> BoundedCommandExecution.setMaxOutputBytes(-1));
        org.junit.jupiter.api.Assertions.assertNotNull(ex1.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(ex2.getMessage());
    }

    @Test
    void getRethrowsIOExceptionCause() throws Exception {
        Future<String> future = failedFuture(new IOException("io"));
        var ex = assertThrows(IOException.class, () -> BoundedCommandExecution.getForTest(future));
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getMessage());
    }

    @Test
    void getRethrowsInterruptedCause() throws Exception {
        Future<String> future = failedFuture(new InterruptedException("int"));
        var ex = assertThrows(InterruptedException.class, () -> BoundedCommandExecution.getForTest(future));
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getMessage());
    }

    @Test
    void getWrapsOtherCauseAsIOException() throws Exception {
        Future<String> future = failedFuture(new IllegalStateException("boom"));
        var ex = assertThrows(IOException.class, () -> BoundedCommandExecution.getForTest(future));
        org.junit.jupiter.api.Assertions.assertNotNull(ex.getMessage());
    }

    private static Future<String> failedFuture(Throwable cause) {
        CompletableFuture<String> f = new CompletableFuture<>();
        f.completeExceptionally(cause);
        return f;
    }

}

