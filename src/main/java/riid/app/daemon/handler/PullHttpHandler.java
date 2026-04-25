package riid.app.daemon.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import riid.app.cli.CliApplication;
import riid.app.service.LoadOutcome;
import riid.app.daemon.guard.PullConcurrencyGuard;
import riid.app.daemon.metrics.DaemonPullHttpMetrics;
import riid.app.daemon.metrics.ImageLoadPipelineMetrics;
import riid.core.logging.MdcContext;

public final class PullHttpHandler extends Handler.Abstract {
    private static final Logger LOGGER = LoggerFactory.getLogger(PullHttpHandler.class);
    private static final String PULL_PATH = "/pull";

    /** Optional: client-supplied correlation id for logs (validated; invalid → new UUID). */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** Optional: alternate header for correlation id (same rules as {@link #HEADER_TRACE_ID}). */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final int TRACE_ID_MAX_LEN = 128;

    /**
     * Reserved repository name: if {@value #ENV_INTERNAL_ERROR_PROBE} is set (non-blank), POST /pull returns HTTP 500
     * without loading (local metrics / Makefile smoke). Unset = normal behaviour.
     */
    public static final String INTERNAL_ERROR_PROBE_REPOSITORY = "__riid_daemon_internal_error_probe__";

    /** When non-blank, enables {@link #INTERNAL_ERROR_PROBE_REPOSITORY} → HTTP 500. */
    public static final String ENV_INTERNAL_ERROR_PROBE = "RIID_DAEMON_INTERNAL_ERROR_PROBE";

    private final String controlConnectorName;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CliApplication.ImageLoader loader;
    private final Set<String> availableRuntimes;
    private final PullConcurrencyGuard concurrencyGuard;
    private final Duration requestTimeout;
    private final ExecutorService pullExecutor;
    private final DaemonPullHttpMetrics pullMetrics;
    private final ImageLoadPipelineMetrics pipelineMetrics;

    public PullHttpHandler(String controlConnectorName,
                    CliApplication.ImageLoader loader,
                    Set<String> availableRuntimes,
                    PullConcurrencyGuard concurrencyGuard,
                    Duration requestTimeout,
                    ExecutorService pullExecutor,
                    DaemonPullHttpMetrics pullMetrics,
                    ImageLoadPipelineMetrics pipelineMetrics) {
        this.controlConnectorName = Objects.requireNonNull(controlConnectorName, "controlConnectorName");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.availableRuntimes = Objects.requireNonNull(availableRuntimes, "availableRuntimes");
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.pullExecutor = Objects.requireNonNull(pullExecutor, "pullExecutor");
        this.pullMetrics = Objects.requireNonNull(pullMetrics, "pullMetrics");
        this.pipelineMetrics = Objects.requireNonNull(pipelineMetrics, "pipelineMetrics");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String connectorName = request.getConnectionMetaData().getConnector().getName();
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();
        LOGGER.info("pull.handle.enter connector={} expectedConnector={} path={} method={} thread={}",
                connectorName, controlConnectorName, path, method, Thread.currentThread().getName());
        if (!controlConnectorName.equals(connectorName)) {
            LOGGER.warn("pull.handle.skip connector mismatch connector={} expected={} path={} method={}",
                    connectorName, controlConnectorName, path, method);
            return false;
        }
        if (!PULL_PATH.equals(path)) {
            LOGGER.warn("pull.handle.skip path mismatch connector={} path={} expectedPath={}",
                    connectorName, path, PULL_PATH);
            return false;
        }
        long t0 = System.nanoTime();
        if (!HttpMethod.POST.is(method)) {
            LOGGER.warn("pull.handle.method_not_allowed connector={} path={} method={}",
                    connectorName, path, method);
            Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
            pullMetrics.record(t0, HttpStatus.METHOD_NOT_ALLOWED_405, "method_not_allowed");
            return true;
        }

        DaemonPullRequest pullRequest;
        try {
            String body = Content.Source.asString(request);
            LOGGER.info("pull.handle.body_read connector={} path={} bytes={}",
                    connectorName, path, body == null ? 0 : body.length());
            pullRequest = mapper.readValue(body, DaemonPullRequest.class);
            LOGGER.info("pull.handle.body_parsed repository={} reference={} runtimeId={}",
                    pullRequest.repository(), pullRequest.reference(), pullRequest.runtimeId());
        } catch (Exception e) {
            LOGGER.error("pull.handle.invalid_request connector={} path={} error={}",
                    connectorName, path, safeMessage(e), e);
            pullMetrics.record(t0, HttpStatus.BAD_REQUEST_400, "invalid_request");
            writeJson(
                    response,
                    callback,
                    HttpStatus.BAD_REQUEST_400,
                    new ErrorResponse("invalid_request", safeMessage(e))
            );
            return true;
        }

        if (pullRequest.repository() == null || pullRequest.repository().isBlank()
                || pullRequest.reference() == null || pullRequest.reference().isBlank()
                || pullRequest.runtimeId() == null || pullRequest.runtimeId().isBlank()) {
            LOGGER.warn("pull.handle.invalid_request missing fields repository={} reference={} runtimeId={}",
                    pullRequest.repository(), pullRequest.reference(), pullRequest.runtimeId());
            pullMetrics.record(t0, HttpStatus.BAD_REQUEST_400, "invalid_request");
            writeJson(response, callback, HttpStatus.BAD_REQUEST_400, new ErrorResponse(
                    "invalid_request",
                    "repository, reference and runtimeId are required"
            ));
            return true;
        }
        if (!availableRuntimes.contains(pullRequest.runtimeId())) {
            LOGGER.warn("pull.handle.unknown_runtime runtimeId={} available={}",
                    pullRequest.runtimeId(), availableRuntimes);
            pullMetrics.record(t0, HttpStatus.UNPROCESSABLE_ENTITY_422, "unknown_runtime");
            writeJson(response, callback, HttpStatus.UNPROCESSABLE_ENTITY_422, new ErrorResponse(
                    "unknown_runtime",
                    "Unknown runtime: " + pullRequest.runtimeId()
            ));
            return true;
        }

        String traceId = resolveTraceId(request);
        LOGGER.info("pull.handle.trace traceId={} repository={} reference={} runtimeId={}",
                traceId, pullRequest.repository(), pullRequest.reference(), pullRequest.runtimeId());
        MdcContext.putTraceId(traceId);
        MdcContext.putComponent("app");
        MdcContext.putOperation("request");
        try {
            String probeEnv = System.getenv(ENV_INTERNAL_ERROR_PROBE);
            if (probeEnv != null && !probeEnv.isBlank()
                    && INTERNAL_ERROR_PROBE_REPOSITORY.equals(pullRequest.repository())) {
                throw new IllegalStateException("daemon internal-error probe (intentional HTTP 500)");
            }
            LOGGER.info("pull.handle.execute.start traceId={} timeoutMs={}", traceId, requestTimeout.toMillis());
            Optional<LoadOutcome> loaded = concurrencyGuard.tryExecute(() -> executePullWithTimeout(pullRequest));
            if (loaded.isEmpty()) {
                LOGGER.warn("pull.handle.overloaded traceId={} maxConcurrentHit=true", traceId);
                pullMetrics.record(t0, HttpStatus.TOO_MANY_REQUESTS_429, "overloaded");
                writeJson(response, callback, HttpStatus.TOO_MANY_REQUESTS_429, new ErrorResponse(
                        "overloaded",
                        "Too many concurrent pull requests"
                ));
                return true;
            }
            LOGGER.info("pull.handle.success traceId={} imagePath={}", traceId, loaded.get().imageRef());
            pullMetrics.record(t0, HttpStatus.OK_200, "success");
            writeJson(response, callback, HttpStatus.OK_200,
                    new DaemonPullResponse("success", loaded.get().imageRef(), null));
        } catch (PullTimeoutException e) {
            LOGGER.error("pull.handle.timeout traceId={} error={}", traceId, safeMessage(e), e);
            pullMetrics.record(t0, HttpStatus.GATEWAY_TIMEOUT_504, "timeout");
            writeJson(response, callback, HttpStatus.GATEWAY_TIMEOUT_504, new ErrorResponse(
                    "timeout",
                    safeMessage(e)
            ));
            return true;
        } catch (Exception e) {
            LOGGER.error("pull.handle.exception traceId={} error={}", traceId, safeMessage(e), e);
            Optional<DaemonPullErrorMapper.MappedHttpError> mapped = DaemonPullErrorMapper.map(e);
            if (mapped.isPresent()) {
                DaemonPullErrorMapper.MappedHttpError m = mapped.get();
                LOGGER.warn("pull.handle.mapped_error traceId={} httpStatus={} code={} message={}",
                        traceId, m.httpStatus(), m.code().jsonValue(), m.message());
                pullMetrics.record(t0, m.httpStatus(), m.code().jsonValue());
                writeJson(response, callback, m.httpStatus(),
                        new ErrorResponse(m.code().jsonValue(), m.message()));
            } else {
                LOGGER.error("pull.handle.unmapped_error traceId={} message={}",
                        traceId, safeMessage(unwrapExecution(e)));
                pullMetrics.record(t0, HttpStatus.INTERNAL_SERVER_ERROR_500, "pull_failed");
                writeJson(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                        new ErrorResponse("pull_failed", safeMessage(unwrapExecution(e))));
            }
        } finally {
            LOGGER.info("pull.handle.exit traceId={}", traceId);
            MdcContext.clearRequestContext();
        }
        return true;
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
        Future<LoadOutcome> future = pullExecutor.submit(
                () -> runLoadWithMdc(mdcSnapshot, pullRequest));
        try {
            LoadOutcome result = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            pipelineMetrics.recordSuccess(start, result.tarBytes());
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            pipelineMetrics.recordTimeout(start);
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
     * Restores pull MDC on the {@link #pullExecutor} thread (virtual threads do not inherit MDC).
     */
    private LoadOutcome runLoadWithMdc(Map<String, String> snapshot, DaemonPullRequest pullRequest) throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (snapshot != null && !snapshot.isEmpty()) {
                MDC.setContextMap(new HashMap<>(snapshot));
            } else {
                MDC.clear();
            }
            return loader.load(pullRequest.repository(), pullRequest.reference(), pullRequest.runtimeId());
        } finally {
            if (previous != null && !previous.isEmpty()) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
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
        String fromHeader = firstNonBlank(
                headers.get(HEADER_TRACE_ID),
                headers.get(HEADER_REQUEST_ID));
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
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':') {
                continue;
            }
            return false;
        }
        return true;
    }

    private void writeJson(Response response, Callback callback, int status, Object payload)
            throws IOException {
        byte[] json = mapper.writeValueAsBytes(payload);
        LOGGER.info("pull.write_json status={} payloadType={} bytes={}",
                status, payload == null ? "null" : payload.getClass().getSimpleName(), json.length);
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
        private PullTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
