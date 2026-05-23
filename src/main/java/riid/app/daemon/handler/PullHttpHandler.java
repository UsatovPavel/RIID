package riid.app.daemon.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

import riid.app.cli.CliApplication;
import riid.app.service.LoadOutcome;
import riid.app.daemon.guard.PullConcurrencyGuard;
import riid.app.daemon.metrics.DaemonPullHttpMetrics;
import riid.app.daemon.metrics.ImageLoadPipelineMetrics;

public final class PullHttpHandler extends Handler.Abstract {
    private static final String PULL_PATH = "/pull";

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
    private final int maxRequestBodyBytes;
    private final Duration requestTimeout;
    private final ExecutorService pullExecutor;
    private final DaemonPullHttpMetrics pullMetrics;
    private final ImageLoadPipelineMetrics pipelineMetrics;

    public PullHttpHandler(String controlConnectorName,
                    CliApplication.ImageLoader loader,
                    Set<String> availableRuntimes,
                    PullConcurrencyGuard concurrencyGuard,
                    int maxRequestBodyBytes,
                    Duration requestTimeout,
                    ExecutorService pullExecutor,
                    DaemonPullHttpMetrics pullMetrics,
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
            writeJson(
                    response,
                    callback,
                    HttpStatus.PAYLOAD_TOO_LARGE_413,
                    new ErrorResponse("request_too_large", "Request body exceeds configured limit")
            );
            return true;
        } catch (Exception e) {
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
            pullMetrics.record(t0, HttpStatus.BAD_REQUEST_400, "invalid_request");
            writeJson(response, callback, HttpStatus.BAD_REQUEST_400, new ErrorResponse(
                    "invalid_request",
                    "repository, reference and runtimeId are required"
            ));
            return true;
        }
        if (!availableRuntimes.contains(pullRequest.runtimeId())) {
            pullMetrics.record(t0, HttpStatus.UNPROCESSABLE_ENTITY_422, "unknown_runtime");
            writeJson(response, callback, HttpStatus.UNPROCESSABLE_ENTITY_422, new ErrorResponse(
                    "unknown_runtime",
                    "Unknown runtime: " + pullRequest.runtimeId()
            ));
            return true;
        }

        try {
            String probeEnv = System.getenv(ENV_INTERNAL_ERROR_PROBE);
            if (probeEnv != null && !probeEnv.isBlank()
                    && INTERNAL_ERROR_PROBE_REPOSITORY.equals(pullRequest.repository())) {
                throw new IllegalStateException("daemon internal-error probe (intentional HTTP 500)");
            }
            Optional<LoadOutcome> loaded = concurrencyGuard.tryExecute(() -> executePullWithTimeout(pullRequest));
            if (loaded.isEmpty()) {
                pullMetrics.record(t0, HttpStatus.TOO_MANY_REQUESTS_429, "overloaded");
                writeJson(response, callback, HttpStatus.TOO_MANY_REQUESTS_429, new ErrorResponse(
                        "overloaded",
                        "Too many concurrent pull requests"
                ));
                return true;
            }
            pullMetrics.record(t0, HttpStatus.OK_200, "success");
            writeJson(response, callback, HttpStatus.OK_200,
                    new DaemonPullResponse("success", loaded.get().imageRef(), null));
        } catch (PullTimeoutException e) {
            pullMetrics.record(t0, HttpStatus.GATEWAY_TIMEOUT_504, "timeout");
            writeJson(response, callback, HttpStatus.GATEWAY_TIMEOUT_504, new ErrorResponse(
                    "timeout",
                    safeMessage(e)
            ));
            return true;
        } catch (Exception e) {
            Optional<DaemonPullErrorMapper.MappedHttpError> mapped = DaemonPullErrorMapper.map(e);
            if (mapped.isPresent()) {
                DaemonPullErrorMapper.MappedHttpError m = mapped.get();
                pullMetrics.record(t0, m.httpStatus(), m.code().jsonValue());
                writeJson(response, callback, m.httpStatus(),
                        new ErrorResponse(m.code().jsonValue(), m.message()));
            } else {
                pullMetrics.record(t0, HttpStatus.INTERNAL_SERVER_ERROR_500, "pull_failed");
                writeJson(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                        new ErrorResponse("pull_failed", safeMessage(unwrapExecution(e))));
            }
        }
        return true;
    }

    private static String readBodyWithLimit(Request request, int maxRequestBodyBytes)
            throws IOException, RequestTooLargeException {
        try {
            byte[] body = Content.Source.asByteArrayAsync(request, maxRequestBodyBytes).join();
            return new String(body, StandardCharsets.UTF_8);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                throw new RequestTooLargeException();
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to read request body", cause);
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
        long start = System.nanoTime();
        CountDownLatch taskTerminated = new CountDownLatch(1);
        Future<LoadOutcome> future = pullExecutor.submit(() -> {
            try {
                return loader.load(pullRequest.repository(), pullRequest.reference(), pullRequest.runtimeId());
            } finally {
                taskTerminated.countDown();
            }
        });
        try {
            LoadOutcome result = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            pipelineMetrics.recordSuccess(start, result.tarBytes());
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

    private void writeJson(Response response, Callback callback, int status, Object payload)
            throws IOException {
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
        private PullTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class RequestTooLargeException extends Exception {
    }
}
