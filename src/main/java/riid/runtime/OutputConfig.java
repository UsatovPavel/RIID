package riid.runtime;

/**
 * Output capture and limit configuration for command execution.
 */
public record OutputConfig(boolean captureStdout, boolean captureStderr, Integer maxStdoutBytes,
        Integer maxStderrBytes) {
    public static OutputConfig defaults() {
        return new OutputConfig(true, true, BoundedCommandExecution.DEFAULT_MAX_OUTPUT_BYTES,
                BoundedCommandExecution.DEFAULT_MAX_OUTPUT_BYTES);
    }

    public OutputConfig withMaxOutputBytes(int maxOutputBytes) {
        return new OutputConfig(captureStdout, captureStderr, maxOutputBytes, maxOutputBytes);
    }

    void validate() {
        if (captureStdout && (maxStdoutBytes == null || maxStdoutBytes <= 0)) {
            throw new IllegalArgumentException("maxStdoutBytes must be positive when stdout capture is enabled");
        }
        if (captureStderr && (maxStderrBytes == null || maxStderrBytes <= 0)) {
            throw new IllegalArgumentException("maxStderrBytes must be positive when stderr capture is enabled");
        }
    }
}
