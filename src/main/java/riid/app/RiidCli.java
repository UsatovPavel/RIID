package riid.app;

/**
 * CLI entrypoint.
 */
public final class RiidCli {
    private RiidCli() { }

    public static void main(String[] args) {
        CliApplication cli = CliApplication.createDefault();
        int code = cli.run(args);
        if (code != CliApplication.EXIT_OK) {
            System.exit(code);
        }
    }
}