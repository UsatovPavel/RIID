package riid.runtime;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import riid.core.logging.LogSecretsRemover;

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

    @Test
    void outputConfigControlsCapture() throws Exception {
        OutputConfig cfg = new OutputConfig(false, true, null, 1024);
        var future = BoundedCommandExecution.run(command(), cfg);
        BoundedCommandExecution.ShellResult result = future.get();

        org.junit.jupiter.api.Assertions.assertEquals("", result.stdout());
        org.junit.jupiter.api.Assertions.assertTrue(result.stderr().contains("stderr-ok"));
    }

    @Test
    void outputConfigDisablesStderrCapture() throws Exception {
        OutputConfig cfg = new OutputConfig(true, false, 1024, null);
        var future = BoundedCommandExecution.run(command(), cfg);
        BoundedCommandExecution.ShellResult result = future.get();

        org.junit.jupiter.api.Assertions.assertTrue(result.stdout().contains("stdout-ok"));
        org.junit.jupiter.api.Assertions.assertEquals("", result.stderr());
    }

    @Test
    void outputConfigEnforcesStdoutLimit() throws Exception {
        OutputConfig cfg = new OutputConfig(true, false, 8, null);
        var future = BoundedCommandExecution.run(floodCommand(32, 0), cfg);

        BoundedCommandExecution.ShellResult result = future.get();
        Assertions.assertTrue(result.stdout().length() <= 8);
    }

    @Test
    void outputConfigEnforcesStderrLimit() throws Exception {
        OutputConfig cfg = new OutputConfig(false, true, null, 8);
        var future = BoundedCommandExecution.run(floodCommand(0, 32), cfg);

        BoundedCommandExecution.ShellResult result = future.get();
        Assertions.assertTrue(result.stderr().length() <= 8);
    }

    @Test
    void maxOutputBytesThrowsOnOverflow() throws Exception {
        var future = BoundedCommandExecution.run(floodCommand(32, 0), 8);
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertLimitExceeded(ex);
    }

    @Test
    void redactCommandForLoggingHidesSecretsInArgs() {
        List<String> raw = List.of(
                "curl",
                "-H",
                "Authorization: Bearer very-secret-token",
                "https://registry.local/v2/repo/blobs/sha256:123?token=sensitive"
        );

        List<String> redacted = raw.stream().map(LogSecretsRemover::sanitizeText).toList();
        String combined = String.join(" ", redacted);

        Assertions.assertFalse(combined.contains("very-secret-token"));
        Assertions.assertFalse(combined.contains("sensitive"));
    }

    private static Future<String> failedFuture(Throwable cause) {
        CompletableFuture<String> f = new CompletableFuture<>();
        f.completeExceptionally(cause);
        return f;
    }

    private static List<String> command() {
        String javaHome = System.getProperty("java.home");
        String javaBin = new File(javaHome, "bin" + File.separator + "java").getPath();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            javaBin = javaBin + ".exe";
        }
        String classpath = System.getProperty("java.class.path");
        return List.of(javaBin, "-cp", classpath, OutputProbeMain.class.getName());
    }

    private static List<String> floodCommand(int outBytes, int errBytes) {
        String javaHome = System.getProperty("java.home");
        String javaBin = new File(javaHome, "bin" + File.separator + "java").getPath();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            javaBin = javaBin + ".exe";
        }
        String classpath = System.getProperty("java.class.path");
        return List.of(javaBin, "-cp", classpath, OutputFloodMain.class.getName(),
                Integer.toString(outBytes), Integer.toString(errBytes));
    }

    private static void assertLimitExceeded(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException re && re.getCause() != null) {
            cause = re.getCause();
        }
        Assertions.assertTrue(
                cause instanceof BoundedCommandExecution.OutputLimitExceededException,
                "Expected OutputLimitExceededException but was: " + cause);
    }

}

