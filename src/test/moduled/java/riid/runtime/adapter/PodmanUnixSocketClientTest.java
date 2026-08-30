package riid.runtime.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import riid.core.fs.HostFilesystemTestSupport;
import riid.core.fs.NioHostFilesystem;

class PodmanUnixSocketClientTest {
    private static final byte[] ARCHIVE = "oci archive".getBytes(StandardCharsets.UTF_8);
    private static final int FINAL_CHUNK_SIZE = 0;
    private static final String TRANSFER_ENCODING = "transfer-encoding";
    private static final String CHUNKED = "chunked";

    @TempDir
    private Path tempDir;

    @Test
    void postsArchiveReadThroughHostFilesystemToVersionedLoadEndpoint() throws Exception {
        try (FakePodmanServer server = new FakePodmanServer(tempDir, 200, "{\"Names\":[\"image\"]}")) {
            HostFilesystemTestSupport fs = HostFilesystemTestSupport.create();
            Path archive = Path.of("/virtual/image.tar");
            fs.write(archive, ARCHIVE);

            PodmanUnixSocketClient client = new PodmanUnixSocketClient(server.containerHost(), fs);
            new PodmanRuntimeAdapter(fs, true, Optional.of(client)).importImage(archive);

            Request request = server.request();
            assertEquals("POST " + PodmanUnixSocketClient.LOAD_PATH + " HTTP/1.1", request.requestLine());
            assertEquals("application/x-tar", request.headers().get("content-type"));
            assertEquals(Integer.toString(ARCHIVE.length), request.headers().get("content-length"));
            assertArrayEquals(ARCHIVE, request.body());
        }
    }

    @Test
    void streamsOciLayoutWithChunkedEncoding() throws Exception {
        Files.writeString(tempDir.resolve("oci-layout"), "layout");
        try (FakePodmanServer server = new FakePodmanServer(tempDir, 200, "{}")) {
            NioHostFilesystem fs = new NioHostFilesystem();
            PodmanUnixSocketClient client = new PodmanUnixSocketClient(server.containerHost(), fs);
            new PodmanRuntimeAdapter(fs, true, Optional.of(client)).importOciLayoutDirectory(tempDir);

            Request request = server.request();
            assertEquals("POST " + PodmanUnixSocketClient.LOAD_PATH + " HTTP/1.1", request.requestLine());
            assertEquals(CHUNKED, request.headers().get(TRANSFER_ENCODING));
            assertTrue(request.body().length > 0, "tar output must be streamed as the request body");
        }
    }

    @Test
    void propagatesPodmanHttpStatusAndBody() throws Exception {
        try (FakePodmanServer server = new FakePodmanServer(tempDir, 500, "load exploded")) {
            Path archive = tempDir.resolve("image.tar");
            Files.write(archive, ARCHIVE);

            NioHostFilesystem fs = new NioHostFilesystem();
            PodmanUnixSocketClient client = new PodmanUnixSocketClient(server.containerHost(), fs);
            IOException error = assertThrows(IOException.class,
                    () -> new PodmanRuntimeAdapter(fs, true, Optional.of(client)).importImage(archive));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertTrue(error.getMessage().contains("load exploded"), error.getMessage());
        }
    }

    @Test
    void removesImageThroughLibpodApiWithEscapedName() throws Exception {
        try (FakePodmanServer server = new FakePodmanServer(tempDir, 200, "{}")) {
            new PodmanUnixSocketClient(server.containerHost(), new NioHostFilesystem())
                    .removeImage("localhost/riid-prefix:1");

            Request request = server.request();
            assertEquals("DELETE /v4.0.0/libpod/images/localhost%2Friid-prefix%3A1?force=true&ignore=true HTTP/1.1",
                    request.requestLine());
            assertEquals("0", request.headers().get("content-length"));
            assertFalse(request.headers().containsKey("content-type"));
        }
    }

    @Test
    void rejectsNonUnixContainerHost() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PodmanUnixSocketClient("tcp://127.0.0.1:8080", new NioHostFilesystem()));
        assertTrue(error.getMessage().contains("unix:///"), error.getMessage());
    }

    private record Request(String requestLine, Map<String, String> headers, byte[] body) {
    }

    private static final class FakePodmanServer implements AutoCloseable {
        private final Path socket;
        private final ServerSocketChannel server;
        private final CompletableFuture<Request> receivedRequest;

        private FakePodmanServer(Path directory, int status, String responseBody) throws IOException {
            socket = directory.resolve("podman-" + System.nanoTime() + ".sock");
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            receivedRequest = CompletableFuture.supplyAsync(() -> {
                try (SocketChannel channel = server.accept()) {
                    Request received = readRequest(Channels.newInputStream(channel));
                    byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    OutputStream output = Channels.newOutputStream(channel);
                    output.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: "
                            + responseBytes.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(responseBytes);
                    output.flush();
                    return received;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        private String containerHost() {
            return "unix://" + socket;
        }

        private Request request() throws Exception {
            try {
                return receivedRequest.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception exception) {
                    throw exception;
                }
                throw e;
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            Files.deleteIfExists(socket);
        }

        private static Request readRequest(InputStream rawInput) throws IOException {
            BufferedInputStream input = new BufferedInputStream(rawInput);
            String requestLine = readLine(input);
            Map<String, String> headers = new LinkedHashMap<>();
            String line = readLine(input);
            while (!line.isEmpty()) {
                int separator = line.indexOf(':');
                headers.put(line.substring(0, separator).toLowerCase(Locale.ROOT),
                        line.substring(separator + 1).trim());
                line = readLine(input);
            }
            byte[] body;
            if (CHUNKED.equalsIgnoreCase(headers.get(TRANSFER_ENCODING))) {
                body = readChunked(input);
            } else {
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                body = input.readNBytes(length);
            }
            return new Request(requestLine, headers, body);
        }

        private static byte[] readChunked(InputStream input) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                int size = Integer.parseInt(readLine(input), 16);
                if (size <= FINAL_CHUNK_SIZE) {
                    readLine(input);
                    return body.toByteArray();
                }
                body.write(input.readNBytes(size));
                readLine(input);
            }
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
                previous = current;
                current = input.read();
            }
            throw new IOException("request ended before CRLF");
        }
    }
}
