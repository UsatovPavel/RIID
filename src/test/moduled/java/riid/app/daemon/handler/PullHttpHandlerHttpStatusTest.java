package riid.app.daemon.handler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import riid.app.cli.CliApplication;
import riid.app.core.model.ImageId;
import riid.app.service.LoadOutcome;
import riid.app.daemon.metrics.DaemonPullHttpMetrics;
import riid.app.daemon.metrics.ImageLoadPipelineMetrics;
import riid.app.core.error.AppError;
import riid.app.core.error.AppException;
import riid.app.daemon.guard.SemaphorePullConcurrencyGuard;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP status mapping for {@code POST /pull} (daemon IPC). Uses in-memory
 * {@link LocalConnector}.
 */
class PullHttpHandlerHttpStatusTest {

    private static LoadOutcome okLoad(String repo, String ref, long tarBytes) {
        return new LoadOutcome(ImageId.fromRegistry("registry-1.docker.io", repo, ref), tarBytes);
    }

    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    private static final class PullRequest {
        private static final String BUSYBOX_REPOSITORY = "library/busybox";
        private static final String LATEST_REFERENCE = "latest";
        private static final String PODMAN_RUNTIME_ID = "podman";
        private static final String BUSYBOX_PULL_BODY = "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}";
        private static final String CLEAN_BODY = "{\"command\":\"CLEAN\"}";
        private static final String JSON_FIELD_CODE = "code";
        private static final String JSON_FIELD_MESSAGE = "message";

        private PullRequest() {
        }
    }

    private static final int FIRST_CALL_NUMBER = 1;
    private static final int FIRST_EXTRA_CONNECTOR = 1;
    private static final int SECOND_EXTRA_CONNECTOR = 2;
    private static final String CONTROL = "control";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNTIMES = Set.of(PullRequest.PODMAN_RUNTIME_ID);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REQUEST_BODY_BYTES = 8192;

    private Server server;
    private LocalConnector connector;
    /**
     * Extra control connectors for concurrent {@link LocalConnector#getResponse}
     */
    private LocalConnector connectorB;
    private LocalConnector connectorC;
    private ExecutorService pullExecutor;
    private PrometheusMeterRegistry meterRegistry;

    @BeforeEach
    void newMeterRegistry() {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    private DaemonPullHttpMetrics pullMetrics() {
        return new DaemonPullHttpMetrics(meterRegistry);
    }

    private ImageLoadPipelineMetrics pipelineLoadMetrics() {
        return new ImageLoadPipelineMetrics(meterRegistry);
    }

    @AfterEach
    @SuppressWarnings("PMD.NullAssignment")
    void tearDown() throws Exception {
        if (server != null && server.isStarted()) {
            server.stop();
        }
        if (pullExecutor != null) {
            pullExecutor.shutdown();
        }
        connectorB = null;
        connectorC = null;
    }

    @Test
    void postPullOkReturns200AndJsonBody() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL,
                (repo, ref, rt) -> okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1), RUNTIMES,
                new SemaphorePullConcurrencyGuard(new Semaphore(4, true)), MAX_REQUEST_BODY_BYTES, LONG_TIMEOUT,
                pullExecutor, pullMetrics(), pipelineLoadMetrics()));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\"," + "\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.OK_200, r.status());
        assertEquals("success", r.json().path("status").asText());
        assertEquals("registry-1.docker.io/library/busybox:latest", r.json().path("imagePath").asText());
    }

    @Test
    void getPullReturns405() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL, (repo, ref, rt) -> okLoad("x", PullRequest.LATEST_REFERENCE, -1),
                RUNTIMES, new SemaphorePullConcurrencyGuard(new Semaphore(4, true)), MAX_REQUEST_BODY_BYTES,
                LONG_TIMEOUT, pullExecutor, pullMetrics(), pipelineLoadMetrics()));

        ParsedResponse r = request("GET /pull HTTP/1.1\r\nHost: local\r\n\r\n");

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED_405, r.status());
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> okLoad("x", PullRequest.LATEST_REFERENCE, -1), pullExecutor));

        ParsedResponse r = request("POST /pull HTTP/1.1\r\n" + "Host: local\r\n" + "Content-Type: application/json\r\n"
                + "Content-Length: 8\r\n" + "\r\n" + "not json");

        assertEquals(HttpStatus.BAD_REQUEST_400, r.status());
        assertEquals("invalid_request", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void requestBodyTooLargeReturns413() throws Exception {
        pullExecutor = newPullExecutor();
        PullHttpHandler handler = new PullHttpHandler(CONTROL,
                (repo, ref, rt) -> okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1), RUNTIMES,
                new SemaphorePullConcurrencyGuard(new Semaphore(4, true)), 64, LONG_TIMEOUT, pullExecutor,
                pullMetrics(), pipelineLoadMetrics());
        startServer(handler);

        String oversizedBody = """
                {
                  "repository":"library/busybox",
                  "reference":"latest",
                  "runtimeId":"podman",
                  "padding":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                }
                """;
        ParsedResponse r = postPull(oversizedBody);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE_413, r.status());
        assertEquals("request_too_large", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void missingRequiredFieldsReturns400() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> okLoad("x", PullRequest.LATEST_REFERENCE, -1), pullExecutor));

        ParsedResponse r = postPull("{\"repository\":\"x\",\"reference\":\"\"}");

        assertEquals(HttpStatus.BAD_REQUEST_400, r.status());
        assertEquals("invalid_request", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void unknownRuntimeReturns422() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> okLoad("x", PullRequest.LATEST_REFERENCE, -1), pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"unknown\"}");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY_422, r.status());
        assertEquals("unknown_runtime", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void overloadReturns429WhenSemaphoreUnavailable() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL,
                (repo, ref, rt) -> okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1), RUNTIMES,
                new SemaphorePullConcurrencyGuard(new Semaphore(0, true)), MAX_REQUEST_BODY_BYTES, LONG_TIMEOUT,
                pullExecutor, pullMetrics(), pipelineLoadMetrics()));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS_429, r.status());
        assertEquals("overloaded", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void cleanReturns200WhenIdle() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL,
                (repo, ref, rt) -> okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1), RUNTIMES,
                new SemaphorePullConcurrencyGuard(new Semaphore(2, true), 2), MAX_REQUEST_BODY_BYTES, LONG_TIMEOUT,
                pullExecutor, pullMetrics(), pipelineLoadMetrics()));

        ParsedResponse r = postClean();

        assertEquals(HttpStatus.OK_200, r.status());
        assertEquals("success", r.json().path("status").asText());
        assertEquals("CLEAN", r.json().path("command").asText());
    }

    @Test
    void cleanReturns409WhenPullIsRunning() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL, (repo, ref, rt) -> {
            entered.countDown();
            try {
                assertTrue(hold.await(5, TimeUnit.MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1);
        }, RUNTIMES, new SemaphorePullConcurrencyGuard(new Semaphore(1, true), 1), MAX_REQUEST_BODY_BYTES, LONG_TIMEOUT,
                pullExecutor, pullMetrics(), pipelineLoadMetrics()), 1);

        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ParsedResponse> pullFuture = clients.submit(() -> postPull(PullRequest.BUSYBOX_PULL_BODY, connector));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "pull should acquire permit before CLEAN");
            ParsedResponse clean = postClean();
            assertEquals(HttpStatus.CONFLICT_409, clean.status());
            assertEquals("clean_busy", clean.json().path(PullRequest.JSON_FIELD_CODE).asText());
            hold.countDown();
            assertEquals(HttpStatus.OK_200, pullFuture.get(30, TimeUnit.SECONDS).status());
        }
    }

    /**
     * With max two concurrent pulls, a third request must get 429 while two loaders
     * are still running. Two {@link LocalConnector}s avoid serializing requests on
     * a single in-memory connection.
     */
    @Test
    void semaphoreAllowsTwoConcurrentPullsThirdGets429UntilRelease() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch bothInLoader = new CountDownLatch(2);
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL, (repo, ref, rt) -> {
            bothInLoader.countDown();
            try {
                assertTrue(hold.await(5, TimeUnit.MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1);
        }, RUNTIMES, new SemaphorePullConcurrencyGuard(new Semaphore(2, true)), MAX_REQUEST_BODY_BYTES, LONG_TIMEOUT,
                pullExecutor, pullMetrics(), pipelineLoadMetrics()), 2);

        String body = PullRequest.BUSYBOX_PULL_BODY;
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ParsedResponse> f1 = clients.submit(() -> postPull(body, connector));
            Future<ParsedResponse> f2 = clients.submit(() -> postPull(body, connectorB));
            assertTrue(bothInLoader.await(10, TimeUnit.SECONDS),
                    "both pulls should acquire permits and block in the loader");
            ParsedResponse r3 = postPull(body, connectorC);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS_429, r3.status());
            assertEquals("overloaded", r3.json().path(PullRequest.JSON_FIELD_CODE).asText());
            hold.countDown();
            assertEquals(HttpStatus.OK_200, f1.get(30, TimeUnit.SECONDS).status());
            assertEquals(HttpStatus.OK_200, f2.get(30, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void loaderTimeoutReturns504() throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL, (repo, ref, rt) -> {
            try {
                hang.await(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1);
        }, RUNTIMES, new SemaphorePullConcurrencyGuard(new Semaphore(4, true)), MAX_REQUEST_BODY_BYTES,
                Duration.ofMillis(120), pullExecutor, pullMetrics(), pipelineLoadMetrics()));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.GATEWAY_TIMEOUT_504, r.status());
        assertEquals("timeout", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        hang.countDown();
    }

    @Test
    void timeoutHoldsPermitUntilWorkerActuallyTerminates() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        pullExecutor = newPullExecutor();
        startServer(new PullHttpHandler(CONTROL, (repo, ref, rt) -> {
            int n = calls.incrementAndGet();
            if (n == FIRST_CALL_NUMBER) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        // Simulates stubborn work that keeps running after cancel(true).
                    }
                }
                return okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1);
            }
            return okLoad(PullRequest.BUSYBOX_REPOSITORY, PullRequest.LATEST_REFERENCE, -1);
        }, RUNTIMES, new SemaphorePullConcurrencyGuard(new Semaphore(1, true)), MAX_REQUEST_BODY_BYTES,
                Duration.ofMillis(80), pullExecutor, pullMetrics(), pipelineLoadMetrics()), 1);

        String body = PullRequest.BUSYBOX_PULL_BODY;
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ParsedResponse> first = clients.submit(() -> postPull(body, connector));
            Thread.sleep(120);
            ParsedResponse second = postPull(body, connectorB);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS_429, second.status());
            assertEquals("overloaded", second.json().path(PullRequest.JSON_FIELD_CODE).asText());

            ParsedResponse firstResult = first.get(5, TimeUnit.SECONDS);
            assertEquals(HttpStatus.GATEWAY_TIMEOUT_504, firstResult.status());

            ParsedResponse third = postPull(body, connectorB);
            assertEquals(HttpStatus.OK_200, third.status());
        }
    }

    @Test
    void loaderFailureReturns500() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new RuntimeException("registry unreachable");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, r.status());
        assertEquals("pull_failed", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertTrue(r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText().contains("registry unreachable"));
    }

    @Test
    void registryHttp404ReturnsNormalized404() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, 404, "Manifest fetch failed"),
                    "Manifest fetch failed: 404");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.NOT_FOUND_404, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.REGISTRY_NOT_FOUND.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertEquals(DaemonPullErrorMapper.REGISTRY_NOT_FOUND_MESSAGE,
                r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText());
    }

    @Test
    void registryNotAllowedReturns403() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            String msg = AppError.RuntimeErrorKind.REGISTRY_NOT_ALLOWED.format("evil.registry");
            throw new AppException(new AppError.RuntimeError(AppError.RuntimeErrorKind.REGISTRY_NOT_ALLOWED, msg), msg);
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.FORBIDDEN_403, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.REGISTRY_NOT_ALLOWED.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void adapterNotFoundReturns422() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            String msg = AppError.RuntimeErrorKind.ADAPTER_NOT_FOUND.format("podman");
            throw new AppException(new AppError.RuntimeError(AppError.RuntimeErrorKind.ADAPTER_NOT_FOUND, msg), msg);
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY_422, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.ADAPTER_NOT_FOUND.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
    }

    @Test
    void clientManifestParseFailureReturns422() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST_PLATFORM,
                            "No manifest list entry for platform linux/amd64"),
                    "No manifest list entry for platform linux/amd64");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/clefos\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY_422, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.MANIFEST_NOT_SATISFIABLE.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertTrue(r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText().contains("linux/amd64"));
    }

    @Test
    void genericManifestParseFailureReturns500() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new ClientException(new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Digest mismatch"),
                    "Manifest digest mismatch: header=sha256:bad computed=sha256:good");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/clefos\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, r.status());
        assertEquals("pull_failed", r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertTrue(r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText().contains("mismatch"));
    }

    @Test
    void registryHttp401Returns404WithUnauthorizedJsonCode() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new ClientException(new ClientError.Http(ClientError.HttpKind.BAD_STATUS, 401, "Unauthorized"),
                    "Manifest fetch failed: 401");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.NOT_FOUND_404, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.REGISTRY_RESPONSE_UNAUTHORIZED.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertEquals(DaemonPullErrorMapper.REGISTRY_NOT_FOUND_MESSAGE,
                r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText());
    }

    @Test
    void registryHttp403Returns404WithResponseForbiddenJsonCode() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new ClientException(new ClientError.Http(ClientError.HttpKind.BAD_STATUS, 403, "Forbidden"),
                    "Blob fetch failed: 403");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.NOT_FOUND_404, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.REGISTRY_RESPONSE_FORBIDDEN.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertEquals(DaemonPullErrorMapper.REGISTRY_NOT_FOUND_MESSAGE,
                r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText());
    }

    @Test
    void registryAuth401Returns404WithUnauthorizedJsonCode() throws Exception {
        pullExecutor = newPullExecutor();
        startServer(newPullHandler((repo, ref, rt) -> {
            throw new ClientException(
                    new ClientError.Auth(ClientError.AuthKind.TOKEN_FAILED, 401, "token endpoint returned status 401"),
                    "SECURITY:AUTH:TOKEN_ENDPOINT_FAILED: token endpoint returned status 401");
        }, pullExecutor));

        ParsedResponse r = postPull(
                "{\"repository\":\"library/busybox\",\"reference\":\"latest\",\"runtimeId\":\"podman\"}");

        assertEquals(HttpStatus.NOT_FOUND_404, r.status());
        assertEquals(DaemonPullErrorMapper.PullErrorCode.REGISTRY_RESPONSE_UNAUTHORIZED.jsonValue(),
                r.json().path(PullRequest.JSON_FIELD_CODE).asText());
        assertEquals(DaemonPullErrorMapper.REGISTRY_NOT_FOUND_MESSAGE,
                r.json().path(PullRequest.JSON_FIELD_MESSAGE).asText());
    }

    private PullHttpHandler newPullHandler(CliApplication.ImageLoader loader, ExecutorService exec) {
        return new PullHttpHandler(CONTROL, loader, RUNTIMES, new SemaphorePullConcurrencyGuard(new Semaphore(4, true)),
                MAX_REQUEST_BODY_BYTES, LONG_TIMEOUT, exec, pullMetrics(), pipelineLoadMetrics());
    }

    private void startServer(Handler handler) throws Exception {
        startServer(handler, 0);
    }

    /**
     * @param extraControlConnectors
     *            how many additional {@link LocalConnector}s named {@link #CONTROL}
     *            to add (for concurrent {@code getResponse} without blocking on the
     *            same connection).
     */
    @SuppressWarnings("PMD.NullAssignment")
    private void startServer(Handler handler, int extraControlConnectors) throws Exception {
        server = new Server();
        connector = new LocalConnector(server);
        connector.setName(CONTROL);
        server.addConnector(connector);
        connectorB = null;
        connectorC = null;
        if (extraControlConnectors >= FIRST_EXTRA_CONNECTOR) {
            connectorB = new LocalConnector(server);
            connectorB.setName(CONTROL);
            server.addConnector(connectorB);
        }
        if (extraControlConnectors >= SECOND_EXTRA_CONNECTOR) {
            connectorC = new LocalConnector(server);
            connectorC.setName(CONTROL);
            server.addConnector(connectorC);
        }
        server.setHandler(handler);
        server.start();
    }

    private static ExecutorService newPullExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private ParsedResponse postPull(String jsonBody) throws Exception {
        return postPull(jsonBody, connector);
    }

    private ParsedResponse postClean() throws Exception {
        return postPull(PullRequest.CLEAN_BODY, connector);
    }

    private ParsedResponse postPull(String jsonBody, LocalConnector conn) throws Exception {
        byte[] utf8 = jsonBody.getBytes(StandardCharsets.UTF_8);
        String raw = "POST /pull HTTP/1.1\r\n" + "Host: local\r\n" + "Content-Type: application/json\r\n"
                + "Content-Length: " + utf8.length + "\r\n" + "\r\n" + jsonBody;
        return request(conn, raw);
    }

    private ParsedResponse request(String rawRequest) throws Exception {
        return request(connector, rawRequest);
    }

    private ParsedResponse request(LocalConnector conn, String rawRequest) throws Exception {
        String raw = conn.getResponse(rawRequest);
        return ParsedResponse.parse(raw);
    }

    private record ParsedResponse(int status, JsonNode json) {
        static ParsedResponse parse(String raw) throws Exception {
            int lineEnd = raw.indexOf("\r\n");
            String statusLine = raw.substring(0, lineEnd);
            int code = Integer.parseInt(statusLine.split("\\s+")[1]);
            int bodyStart = raw.indexOf("\r\n\r\n");
            String body = bodyStart >= 0 ? raw.substring(bodyStart + 4) : "";
            JsonNode json = parseJsonBody(body);
            return new ParsedResponse(code, json);
        }

        private static JsonNode parseJsonBody(String body) throws Exception {
            if (body.isBlank()) {
                return MAPPER.createObjectNode();
            }
            String trimmed = body.stripLeading();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(body);
        }
    }
}
