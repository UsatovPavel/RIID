package riid.app.daemon.handler;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.MDC;

import riid.app.cli.CliApplication;
import riid.app.service.LoadOutcome;
import riid.app.daemon.guard.PullConcurrencyGuard;
import riid.app.daemon.metrics.DaemonPullHttpMetrics;
import riid.app.daemon.metrics.ImageLoadPipelineMetrics;
import riid.core.logging.MdcContext;
import riid.runtime.adapter.RuntimeId;

public final class PullHttpHandler extends Handler.Abstract {
    private static final String PULL_PATH = "/pull";

    /**
     * Optional: client-supplied correlation id for logs (validated; invalid → new
     * UUID).
     */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * Optional: alternate header for correlation id (same rules as
     * {@link #HEADER_TRACE_ID}).
     */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final int TRACE_ID_MAX_LEN = 128;

    /**
     * Reserved repository name: if {@value #ENV_INTERNAL_ERROR_PROBE} is set
     * (non-blank), POST /pull returns HTTP 500 without loading (local metrics /
     * Makefile smoke). Unset = normal behaviour.
     */
    public static final String INTERNAL_ERROR_PROBE_REPOSITORY = "__riid_daemon_internal_error_probe__";

    /**
     * When non-blank, enables {@link #INTERNAL_ERROR_PROBE_REPOSITORY} → HTTP 500.
     */
    public static final String ENV_INTERNAL_ERROR_PROBE = "RIID_DAEMON_INTERNAL_ERROR_PROBE";
    private static final String ERROR_INVALID_REQUEST = "invalid_request";

    private final String controlConnectorName;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CliApplication.ImageLoader loader;
    private final Set<RuntimeId> availableRuntimes;
    private final PullConcurrencyGuard concurrencyGuard;
    private final int maxRequestBodyBytes;
    private final Duration requestTimeout;
    private final ExecutorService pullExecutor;
    private final DaemonPullHttpMetrics pullMetrics;
    private final ImageLoadPipelineMetrics pipelineMetrics;

    public PullHttpHandler(String controlConnectorName, CliApplication.ImageLoader loader,
            Set<RuntimeId> availableRuntimes, PullConcurrencyGuard concurrencyGuard, int maxRequestBodyBytes,
            Duration requestTimeout, ExecutorService pullExecutor, DaemonPullHttpMetrics pullMetrics,
            ImageLoadPipelineMetrics pipelineMetrics) {
        this.controlConnectorName = Objects.requireNonNull(controlConnectorName, "controlConnectorName");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.availableRuntimes = Objects.requireNonNull(availableRuntimes, "availableRuntimes");
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard");
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.pullExecutor = Objects.requireNonNull(pullExecutor, "pullExecutor");
        this.pullMetrics = Objects.requireNonNull(pullMetrics, "pullMetrics");
        this.pipelineMetrics = Objects.requireNonNull(pipelineMetrics, "pipelineMetrics");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!controlConnectorName.equals(request.getConnectionMetaData().getConnector().getName())) {
            return false;
        }
        if (!PULL_PATH.equals(request.getHttpURI().getPath())) {
            return false;
        }
        long t0 = System.nanoTime();
        if (!HttpMethod.POST.is(request.getMethod())) {
            Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
            pullMetrics.record(t0, HttpStatus.METHOD_NOT_ALLOWED_405, "method_not_allowed");
            return true;
        }

        DaemonPullRequest pullRequest;
        try {
            long declaredContentLength = request.getLength();
            if (declaredContentLength > maxRequestBodyBytes) {
                throw new RequestTooLargeException();
            }
            String body = readBodyWithLimit(request, maxRequestBodyBytes);
            pullRequest = mapper.readValue(body, DaemonPullRequest.class);
        } catch (RequestTooLargeException e) {
            writeJson(response, callback, HttpStatus.PAYLOAD_TOO_LARGE_413,
                    new ErrorResponse("request_too_large", "Request body exceeds configured limit"));
            return true;
        } catch (Exception e) {
            pullMetrics.record(t0, HttpStatus.BAD_REQUEST_400, ERROR_INVALID_REQUEST);
            writeJson(response, callback, HttpStatus.BAD_REQUEST_400,
                    new ErrorResponse(ERROR_INVALID_REQUEST, safeMessage(e)));
            return true;
        }

        if (pullRequest.repository() == null || pullRequest.repository().isBlank() || pullRequest.reference() == null
                || pullRequest.reference().isBlank() || pullRequest.runtimeId() == null
                || pullRequest.runtimeId().isBlank()) {
            pullMetrics.record(t0, HttpStatus.BAD_REQUEST_400, ERROR_INVALID_REQUEST);
            writeJson(response, callback, HttpStatus.BAD_REQUEST_400,
                    new ErrorResponse(ERROR_INVALID_REQUEST, "repository, reference and runtimeId are required"));
            return true;
        }
        RuntimeId requestedRuntime = parseRuntimeId(pullRequest.runtimeId());
        if (requestedRuntime == null || !availableRuntimes.contains(requestedRuntime)) {
            pullMetrics.record(t0, HttpStatus.UNPROCESSABLE_ENTITY_422, "unknown_runtime");
            writeJson(response, callback, HttpStatus.UNPROCESSABLE_ENTITY_422,
                    new ErrorResponse("unknown_runtime", "Unknown runtime: " + pullRequest.runtimeId()));
            return true;
        }

        String traceId = resolveTraceId(request);
        MdcContext.putTraceId(traceId);
        MdcContext.putComponent("app");
        MdcContext.putOperation("request");
        try {
            String probeEnv = System.getenv(ENV_INTERNAL_ERROR_PROBE);
            if (probeEnv != null && !probeEnv.isBlank()
                    && INTERNAL_ERROR_PROBE_REPOSITORY.equals(pullRequest.repository())) {
                throw new IllegalStateException("daemon internal-error probe (intentional HTTP 500)");
            }
            Optional<LoadOutcome> loaded = concurrencyGuard.tryExecute(() -> executePullWithTimeout(pullRequest));
            if (loaded.isEmpty()) {
                pullMetrics.record(t0, HttpStatus.TOO_MANY_REQUESTS_429, "overloaded");
                writeJson(response, callback, HttpStatus.TOO_MANY_REQUESTS_429,
                        new ErrorResponse("overloaded", "Too many concurrent pull requests"));
                return true;
            }
            pullMetrics.record(t0, HttpStatus.OK_200, "success");
            writeJson(response, callback, HttpStatus.OK_200,
                    new DaemonPullResponse("success", loaded.get().imageRef(), null));
        } catch (PullTimeoutException e) {
            pullMetrics.record(t0, HttpStatus.GATEWAY_TIMEOUT_504, "timeout");
            writeJson(response, callback, HttpStatus.GATEWAY_TIMEOUT_504, new ErrorResponse("timeout", safeMessage(e)));
            return true;
        } catch (Exception e) {
            Optional<DaemonPullErrorMapper.MappedHttpError> mapped = DaemonPullErrorMapper.map(e);
            if (mapped.isPresent()) {
                DaemonPullErrorMapper.MappedHttpError m = mapped.get();
                pullMetrics.record(t0, m.httpStatus(), m.code().jsonValue());
                writeJson(response, callback, m.httpStatus(), new ErrorResponse(m.code().jsonValue(), m.message()));
            } else {
                pullMetrics.record(t0, HttpStatus.INTERNAL_SERVER_ERROR_500, "pull_failed");
                writeJson(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                        new ErrorResponse("pull_failed", safeMessage(unwrapExecution(e))));
            }
        } finally {
            MdcContext.clearRequestContext();
        }
        return true;
    }

    private static String readBodyWithLimit(Request request, int maxRequestBodyBytes)
            throws IOException, RequestTooLargeException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            readChunksWithLimit(request, maxRequestBodyBytes, out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Jetty may still signal limit-related or chunk-state issues as
            // IllegalArgumentException.
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("limit")) {
                throw new RequestTooLargeException();
            }
            throw new IOException("Failed to read request body", e);
        }
    }

    private static void readChunksWithLimit(Request request, int maxRequestBodyBytes, ByteArrayOutputStream out)
            throws IOException, RequestTooLargeException {
        int totalBytes = 0;
        while (true) {
            Content.Chunk chunk = request.read();
            if (chunk == null) {
                throw new IOException("Request body stream ended unexpectedly");
            }
            try {
                if (Content.Chunk.isFailure(chunk)) {
                    Throwable failure = chunk.getFailure();
                    if (failure instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Failed to read request body", failure);
                }
                if (chunk.hasRemaining()) {
                    totalBytes = appendChunk(out, chunk.getByteBuffer(), totalBytes, maxRequestBodyBytes);
                }
                if (chunk.isLast()) {
                    return;
                }
            } finally {
                chunk.release();
            }
        }
    }

    private static int appendChunk(ByteArrayOutputStream out, ByteBuffer buffer, int totalBytes,
            int maxRequestBodyBytes) throws RequestTooLargeException {
        int remaining = buffer.remaining();
        int updatedTotal = totalBytes + remaining;
        if (updatedTotal > maxRequestBodyBytes) {
            throw new RequestTooLargeException();
        }
        byte[] bytes = new byte[remaining];
        buffer.get(bytes);
        out.write(bytes, 0, bytes.length);
        return updatedTotal;
    }

    private static RuntimeId parseRuntimeId(String raw) {
        try {
            return RuntimeId.from(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Throwable unwrapExecution(Throwable e) {
        Throwable u = e;
        while (u instanceof ExecutionException && u.getCause() != null) {
            u = u.getCause();
        }
        return u;
    }

    private LoadOutcome executePullWithTimeout(DaemonPullRequest pullRequest) throws Exception {
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        long start = System.nanoTime();
        CountDownLatch taskTerminated = new CountDownLatch(1);
        Future<LoadOutcome> future = pullExecutor.submit(() -> {
            try {
                return runLoadWithMdc(mdcSnapshot, pullRequest);
            } finally {
                taskTerminated.countDown();
            }
        });
        try {
            LoadOutcome result = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            pipelineMetrics.recordSuccess(start, result.payloadBytes());
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            pipelineMetrics.recordTimeout(start);
            awaitTaskTermination(taskTerminated, future);
            throw new PullTimeoutException("Pull request timed out", e);
        } catch (ExecutionException e) {
            pipelineMetrics.recordFailure(start);
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        } catch (InterruptedException e) {
            pipelineMetrics.recordFailure(start);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Restores pull MDC on the {@link #pullExecutor} thread (virtual threads do not
     * inherit MDC).
     */
    private LoadOutcome runLoadWithMdc(Map<String, String> snapshot, DaemonPullRequest pullRequest) throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (snapshot != null && !snapshot.isEmpty()) {
                MDC.setContextMap(new HashMap<>(snapshot));
            } else {
                MDC.clear();
            }
            return loader.load(pullRequest.repository(), pullRequest.reference(),
                    parseRuntimeId(pullRequest.runtimeId()));
        } finally {
            if (previous != null && !previous.isEmpty()) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }

    private static void awaitTaskTermination(CountDownLatch taskTerminated, Future<LoadOutcome> future)
            throws PullTimeoutException {
        while (true) {
            try {
                taskTerminated.await();
                return;
            } catch (InterruptedException ie) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new PullTimeoutException("Interrupted while waiting for pull task cancellation", ie);
            }
        }
    }

    static String resolveTraceId(Request request) {
        return traceIdFromHttpFields(request.getHeaders());
    }

    /**
     * Shared header logic (testable without a full {@link Request}).
     */
    static String traceIdFromHttpFields(HttpFields headers) {
        if (headers == null) {
            return UUID.randomUUID().toString();
        }
        String fromHeader = firstNonBlank(headers.get(HEADER_TRACE_ID), headers.get(HEADER_REQUEST_ID));
        if (fromHeader != null) {
            String trimmed = fromHeader.trim();
            if (isValidClientTraceId(trimmed)) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    static boolean isValidClientTraceId(String value) {
        if (value.isEmpty() || value.length() > TRACE_ID_MAX_LEN) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':')) {
                return false;
            }
        }
        return true;
    }

    private void writeJson(Response response, Callback callback, int status, Object payload) throws IOException {
        byte[] json = mapper.writeValueAsBytes(payload);
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        ByteBuffer buffer = BufferUtil.toBuffer(json);
        response.write(true, buffer, callback);
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    record DaemonPullRequest(String repository, String reference, String runtimeId) {
    }

    record DaemonPullResponse(String status, String imagePath, String error) {
    }

    record ErrorResponse(String code, String message) {
    }

    private static final class PullTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;

        private PullTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
