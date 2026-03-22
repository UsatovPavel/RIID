package riid.app.daemon;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

final class MetricsHttpHandler extends Handler.Abstract {
    private static final String METRICS_PATH = "/metrics";
    private static final byte[] PLACEHOLDER_METRICS = "# metrics placeholder\n"
            .getBytes(StandardCharsets.UTF_8);

    private final String metricsConnectorName;

    MetricsHttpHandler(String metricsConnectorName) {
        this.metricsConnectorName = Objects.requireNonNull(metricsConnectorName, "metricsConnectorName");
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
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.write(true, BufferUtil.toBuffer(PLACEHOLDER_METRICS), callback);
        return true;
    }
}
