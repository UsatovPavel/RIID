package riid.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

@Tag("filesystem")
class RuntimeAdaptersTest {

    private static final String TAR_SUFFIX = ".tar";
    private static final String PAYLOAD = "data";
    private static final String ERR = "err";
    private final HostFilesystem fs = new NioHostFilesystem();

    @Test
    void podmanFailsOnMissingFile() {
        PodmanRuntimeAdapter adapter = new PodmanRuntimeAdapter();
        Path missing = Path.of("non-existent-image.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    void podmanThrowsOnNonZeroExit() throws Exception {
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "podman-", TAR_SUFFIX);
        fs.writeString(tmp, PAYLOAD);
        PodmanRuntimeAdapter adapter = new TestPodmanAdapter(1, "out", ERR);
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "podman load failed");
        assertContains(ex.getMessage(), "err");
    }

    @Test
    void podmanSuccess() throws Exception {
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "podman-", TAR_SUFFIX);
        fs.writeString(tmp, PAYLOAD);
        PodmanRuntimeAdapter adapter = new TestPodmanAdapter(0, "ok", "");
        assertDoesNotThrow(() -> adapter.importImage(tmp));
    }

    @Test
    void portoFailsOnMissingFile() {
        PortoRuntimeAdapter adapter = new PortoRuntimeAdapter();
        Path missing = Path.of("non-existent-porto.tar");
        assertThrows(IOException.class, () -> adapter.importImage(missing));
    }

    @Test
    void portoThrowsOnNonZeroExit() throws Exception {
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "porto-", TAR_SUFFIX);
        fs.writeString(tmp, PAYLOAD);
        PortoRuntimeAdapter adapter = new TestPortoAdapter(2, "o", ERR);
        IOException ex = assertThrows(IOException.class, () -> adapter.importImage(tmp));
        assertContains(ex.getMessage(), "portoctl layer import failed");
        assertContains(ex.getMessage(), "err");
    }

    @Test
    void portoSuccess() throws Exception {
        Path tmp = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, "porto-", TAR_SUFFIX);
        fs.writeString(tmp, PAYLOAD);
        PortoRuntimeAdapter adapter = new TestPortoAdapter(0, "ok", "");
        assertDoesNotThrow(() -> adapter.importImage(tmp));
    }

    private static void assertContains(String msg, String fragment) {
        if (msg == null || !msg.contains(fragment)) {
            throw new AssertionError("Expected \"" + fragment + "\" in: " + msg);
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestPodmanAdapter extends PodmanRuntimeAdapter {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private TestPodmanAdapter(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        protected BoundedCommandExecution.ShellResult runCommand(List<String> command) {
            return new BoundedCommandExecution.ShellResult(exitCode, stdout, stderr);
        }
    }

    @SuppressWarnings("PMD.TestClassWithoutTestCases")
    private static final class TestPortoAdapter extends PortoRuntimeAdapter {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private TestPortoAdapter(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        protected BoundedCommandExecution.ShellResult runCommand(List<String> command) {
            return new BoundedCommandExecution.ShellResult(exitCode, stdout, stderr);
        }
    }

}



