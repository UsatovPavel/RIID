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

public final class MetricsHttpHandler extends Handler.Abstract {
    private static final String METRICS_PATH = "/metrics";

    private final String metricsConnectorName;
    private final PrometheusMeterRegistry prometheusRegistry;

    public MetricsHttpHandler(String metricsConnectorName, PrometheusMeterRegistry prometheusRegistry) {
        this.metricsConnectorName = Objects.requireNonNull(metricsConnectorName, "metricsConnectorName");
        this.prometheusRegistry = Objects.requireNonNull(prometheusRegistry, "prometheusRegistry");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        if (!metricsConnectorName.equals(request.getConnectionMetaData().getConnector().getName())) {
            return false;
        }
        if (!METRICS_PATH.equals(request.getHttpURI().getPath())) {
            return false;
        }
        if (!HttpMethod.GET.is(request.getMethod())) {
            Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
            return true;
        }

        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        byte[] body = prometheusRegistry.scrape().getBytes(StandardCharsets.UTF_8);
        response.write(true, BufferUtil.toBuffer(body), callback);
        return true;
    }
}
