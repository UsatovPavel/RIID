package riid.app;

import org.junit.jupiter.api.Test;
import riid.client.core.config.RegistryEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ImageIdTest {

    @Test
    void registryForUsesPortWhenPositive() {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry.example", 5000, null);
        assertEquals("registry.example:5000", ImageId.registryFor(endpoint));
    }

    @Test
    void fromRegistryUsesDigestWhenReferenceIsSha256() {
        ImageId id = ImageId.fromRegistry("registry.example", "repo/app", "sha256:abcd");

        assertEquals("registry.example", id.registry());
        assertEquals("repo/app", id.name());
        assertNull(id.tag());
        assertEquals("sha256:abcd", id.digest());
    }

    @Test
    void fromRegistryUsesTagWhenReferenceIsNotDigest() {
        ImageId id = ImageId.fromRegistry("registry.example", "repo/app", "latest");

        assertEquals("registry.example", id.registry());
        assertEquals("repo/app", id.name());
        assertEquals("latest", id.tag());
        assertNull(id.digest());
    }

    @Test
    void withDigestReturnsSameInstanceWhenBlank() {
        ImageId original = new ImageId("registry.example", "repo/app", "latest", null);
        assertSame(original, original.withDigest(null));
        assertSame(original, original.withDigest(""));
        assertSame(original, original.withDigest("   "));
    }
}

