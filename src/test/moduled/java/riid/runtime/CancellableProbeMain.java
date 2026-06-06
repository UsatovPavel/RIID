package riid.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/**
 * Helper main for cancellation tests.
 */
public final class CancellableProbeMain {
    private CancellableProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path started = Path.of(args[0]);
        Path stopped = Path.of(args[1]);
        Files.writeString(started, "started", StandardCharsets.UTF_8);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.writeString(stopped, "stopped", StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // best effort
            }
        }));
        while (true) {
            Thread.sleep(1_000);
        }
    }
}
