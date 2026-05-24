package riid.runtime;

/**
 * Helper main for output capture tests.
 */
public final class OutputProbeMain {
    private OutputProbeMain() {
    }

    public static void main(String[] args) {
        System.out.print("stdout-ok");
        System.err.print("stderr-ok");
    }
}
