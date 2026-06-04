package riid.runtime;

/**
 * Helper main that prints a configurable amount of output.
 */
public final class OutputFloodMain {
    private OutputFloodMain() {
    }

    public static void main(String[] args) {
        int outBytes = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int errBytes = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        if (outBytes > 0) {
            System.out.print("o".repeat(outBytes));
        }
        if (errBytes > 0) {
            System.err.print("e".repeat(errBytes));
        }
    }
}
