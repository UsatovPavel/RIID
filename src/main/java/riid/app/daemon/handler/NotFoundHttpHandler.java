package riid.app.daemon.handler;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Last handler in chain: always terminates request with HTTP 404.
 */
public final class NotFoundHttpHandler extends Handler.Abstract {
    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
        return true;
    }
}
