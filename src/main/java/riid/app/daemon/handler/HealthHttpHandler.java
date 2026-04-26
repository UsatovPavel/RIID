package riid.app.daemon.handler;

import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * Lightweight daemon liveness endpoint for troubleshooting request routing.
 */
public final class HealthHttpHandler extends Handler.Abstract {
    private static final String HEALTH_PATH = "/healthz";
    private static final byte[] BODY = "ok\n".getBytes(StandardCharsets.UTF_8);

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        if (!HEALTH_PATH.equals(request.getHttpURI().getPath())) {
            return false;
        }
        if (!HttpMethod.GET.is(request.getMethod())) {
            Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
            return true;
        }

        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.write(true, BufferUtil.toBuffer(BODY), callback);
        return true;
    }
}
