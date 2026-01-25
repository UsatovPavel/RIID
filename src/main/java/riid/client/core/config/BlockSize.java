package riid.client.core.config;

/**
 * Common block sizes for ranged blob downloads.
 */
public enum BlockSize {
    MB1(1),
    MB8(8),
    MB16(16),
    MB32(32),
    MB128(128),
    MB256(256);

    private static final long BYTES_IN_MB = 1024L * 1024L;

    private final long bytes;

    BlockSize(long megabytes) {
        this.bytes = megabytes * BYTES_IN_MB;
    }

    public long bytes() {
        return bytes;
    }
}

