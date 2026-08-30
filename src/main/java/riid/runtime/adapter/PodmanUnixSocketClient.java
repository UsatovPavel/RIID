package riid.runtime.adapter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;

import riid.core.fs.HostFilesystem;

/** Minimal HTTP/1.1 client for the Podman service exposed on a Unix socket. */
final class PodmanUnixSocketClient {
    private static final String PODMAN_HOST_ENV = "CONTAINER_HOST";
    static final String LOAD_PATH = "/v4.0.0/libpod/images/load";
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Path socketPath;
    private final HostFilesystem fs;

    static Optional<PodmanUnixSocketClient> fromEnvironment(HostFilesystem fs) {
        String containerHost = System.getenv(PODMAN_HOST_ENV);
        return containerHost == null || containerHost.isBlank()
                ? Optional.empty()
                : Optional.of(new PodmanUnixSocketClient(containerHost, fs));
    }

    PodmanUnixSocketClient(String containerHost, HostFilesystem fs) {
        this.fs = Objects.requireNonNull(fs, "fs");
        URI uri;
        try {
            uri = URI.create(containerHost);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid CONTAINER_HOST: " + containerHost, e);
        }
        if (!"unix".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null || uri.getPath().isBlank()
                || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("CONTAINER_HOST must be unix:///absolute/path: " + containerHost);
        }
        socketPath = Path.of(uri.getPath());
        if (!socketPath.isAbsolute()) {
            throw new IllegalArgumentException("CONTAINER_HOST socket path must be absolute: " + containerHost);
        }
    }

    void loadArchive(Path archive) throws IOException {
        long length = fs.size(archive);
        try (InputStream body = fs.newInputStream(archive)) {
            request("POST", LOAD_PATH, body, length);
        }
    }

    void loadArchive(InputStream archive) throws IOException {
        request("POST", LOAD_PATH, archive, -1);
    }

    void removeImage(String image) throws IOException {
        request("DELETE", "/v4.0.0/libpod/images/" + encodePathSegment(image) + "?force=true&ignore=true",
                InputStream.nullInputStream(), 0);
    }

    @SuppressWarnings("PMD.CloseResource")
    private void request(String method, String path, InputStream body, long contentLength) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            OutputStream output = Channels.newOutputStream(channel);
            writeHeaders(output, method, path, contentLength);
            if (contentLength >= 0) {
                body.transferTo(output);
            } else {
                writeChunked(output, body);
            }
            output.flush();
            HttpResponse response = readResponse(Channels.newInputStream(channel));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Podman API " + method + " " + path + " failed with HTTP " + response.statusCode()
                        + ": " + response.body());
            }
        }
    }

    private static void writeHeaders(OutputStream output, String method, String path, long contentLength)
            throws IOException {
        StringBuilder headers = new StringBuilder().append(method).append(' ').append(path)
                .append(" HTTP/1.1\r\nHost: d\r\nConnection: close\r\n");
        if (contentLength >= 0) {
            headers.append("Content-Length: ").append(contentLength).append("\r\n");
        } else {
            headers.append("Transfer-Encoding: chunked\r\n");
        }
        if (!"DELETE".equals(method)) {
            headers.append("Content-Type: application/x-tar\r\n");
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeChunked(OutputStream output, InputStream body) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read = body.read(buffer);
        while (read != -1) {
            output.write(Integer.toHexString(read).getBytes(StandardCharsets.US_ASCII));
            output.write(CRLF);
            output.write(buffer, 0, read);
            output.write(CRLF);
            read = body.read(buffer);
        }
        output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static HttpResponse readResponse(InputStream rawInput) throws IOException {
        BufferedInputStream input = new BufferedInputStream(rawInput);
        String statusLine = readLine(input);
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/")) {
            throw new IOException("Invalid Podman API response status line: " + statusLine);
        }
        int statusCode;
        try {
            statusCode = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Podman API status code: " + statusLine, e);
        }

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String line = readLine(input);
        while (!line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Invalid Podman API response header: " + line);
            }
            headers.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            line = readLine(input);
        }

        byte[] body;
        String transferEncoding = headers.getOrDefault("Transfer-Encoding", "").toLowerCase(Locale.ROOT);
        if (transferEncoding.contains("chunked")) {
            body = readChunkedBody(input);
        } else if (headers.containsKey("Content-Length")) {
            body = readFixedBody(input, parseContentLength(headers.get("Content-Length")));
        } else {
            body = readUntilEof(input);
        }
        return new HttpResponse(statusCode, new String(body, StandardCharsets.UTF_8));
    }

    private static long parseContentLength(String value) throws IOException {
        try {
            long length = Long.parseLong(value);
            if (length < 0) {
                throw new NumberFormatException("negative length");
            }
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Podman API Content-Length: " + value, e);
        }
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input);
            int extension = sizeLine.indexOf(';');
            String sizeText = extension >= 0 ? sizeLine.substring(0, extension) : sizeLine;
            long size;
            try {
                size = Long.parseLong(sizeText.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Podman API chunk size: " + sizeLine, e);
            }
            if (size < 0) {
                throw new IOException("Invalid Podman API chunk size: " + sizeLine);
            }
            if (size == 0) {
                while (!readLine(input).isEmpty()) {
                    // Ignore trailers.
                }
                return captured.toByteArray();
            }
            int remainingCapacity = MAX_RESPONSE_BYTES - captured.size();
            captured.writeBytes(readLimited(input, size, remainingCapacity));
            if (!readLine(input).isEmpty()) {
                throw new IOException("Invalid Podman API chunk terminator");
            }
        }
    }

    private static byte[] readFixedBody(InputStream input, long length) throws IOException {
        return readLimited(input, length, MAX_RESPONSE_BYTES);
    }

    private static byte[] readUntilEof(InputStream input) throws IOException {
        try (InputStream limited = BoundedInputStream.builder().setInputStream(input)
                .setMaxCount(MAX_RESPONSE_BYTES).setPropagateClose(false).get()) {
            return IOUtils.toByteArray(limited);
        }
    }

    private static byte[] readLimited(InputStream input, long length, int maxCapturedBytes) throws IOException {
        long capturedLength = Math.min(length, maxCapturedBytes);
        byte[] captured = IOUtils.toByteArray(input, capturedLength);
        IOUtils.skipFully(input, length - capturedLength);
        return captured;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current = input.read();
        while (current != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(current);
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("Podman API response line exceeds " + MAX_LINE_BYTES + " bytes");
            }
            previous = current;
            current = input.read();
        }
        throw new EOFException("Podman API response ended before CRLF");
    }

    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int character = Byte.toUnsignedInt(item);
            if (character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || character == '-' || character == '.' || character == '_'
                    || character == '~') {
                encoded.append((char) character);
            } else {
                encoded.append('%').append(HEX.toHexDigits(item));
            }
        }
        return encoded.toString();
    }

    private record HttpResponse(int statusCode, String body) {
    }
}
