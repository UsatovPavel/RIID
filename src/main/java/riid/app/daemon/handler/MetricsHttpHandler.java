package riid.app.daemon.handler;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetricsHttpHandler extends Handler.Abstract {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsHttpHandler.class);
    private static final String METRICS_PATH = "/metrics";

    private final String metricsConnectorName;
    private final PrometheusMeterRegistry prometheusRegistry;

    public MetricsHttpHandler(String metricsConnectorName, PrometheusMeterRegistry prometheusRegistry) {
        this.metricsConnectorName = Objects.requireNonNull(metricsConnectorName, "metricsConnectorName");
        this.prometheusRegistry = Objects.requireNonNull(prometheusRegistry, "prometheusRegistry");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String connectorName = request.getConnectionMetaData().getConnector().getName();
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();
        LOGGER.info("metrics.handle.enter connector={} expectedConnector={} path={} method={} thread={}",
                connectorName, metricsConnectorName, path, method, Thread.currentThread().getName());
        if (!metricsConnectorName.equals(connectorName)) {
            LOGGER.warn("metrics.handle.skip connector mismatch connector={} expected={} path={} method={}",
                    connectorName, metricsConnectorName, path, method);
            return false;
        }
        if (!METRICS_PATH.equals(path)) {
            LOGGER.warn("metrics.handle.skip path mismatch connector={} path={} expectedPath={}",
                    connectorName, path, METRICS_PATH);
            return false;
        }
        if (!HttpMethod.GET.is(method)) {
            LOGGER.warn("metrics.handle.method_not_allowed connector={} path={} method={}",
                    connectorName, path, method);
            Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
            return true;
        }

        LOGGER.info("metrics.handle.scrape.start connector={} path={}", connectorName, path);
        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        byte[] body = prometheusRegistry.scrape().getBytes(StandardCharsets.UTF_8);
        LOGGER.info("metrics.handle.scrape.done connector={} path={} bytes={}", connectorName, path, body.length);
        response.write(true, BufferUtil.toBuffer(body), callback);
        LOGGER.info("metrics.handle.exit connector={} path={} status={}", connectorName, path, HttpStatus.OK_200);
        return true;
    }
}
