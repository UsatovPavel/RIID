package riid.app.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import riid.app.core.model.ImageId;
import riid.app.service.LoadOutcome;

class DaemonRuntimeContextTest {

    @Test
    void closesResourcesInReverseRegistrationOrder() throws Exception {
        List<String> closeOrder = new ArrayList<>();
        AutoCloseable first = () -> closeOrder.add("first");
        AutoCloseable second = () -> closeOrder.add("second");

        DaemonRuntimeContext context = new DaemonRuntimeContext(
                (repo, ref, runtime) -> new LoadOutcome(ImageId.fromRegistry("registry-1.docker.io", repo, ref), -1L),
                List.of(first, second));

        context.close();

        assertEquals(List.of("second", "first"), closeOrder);
    }
}
