package riid.app;

import org.junit.jupiter.api.Test;
import riid.client.core.config.RegistryEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ImageIdTest {
    private static final String REGISTRY = "registry.example";
    private static final String REPO = "repo/app";

    @Test
    void registryForUsesPortWhenPositive() {
        RegistryEndpoint endpoint = new RegistryEndpoint("https", REGISTRY, 5000, null);
        assertEquals(REGISTRY + ":5000", endpoint.registryName());
    }

    @Test
    void fromRegistryUsesDigestWhenReferenceIsSha256() {
        ImageId id = ImageId.fromRegistry(REGISTRY, REPO, "sha256:abcd");

        assertEquals(REGISTRY, id.registry());
        assertEquals(REPO, id.name());
        assertNull(id.tag());
        assertEquals("sha256:abcd", id.digest());
    }

    @Test
    void fromRegistryUsesTagWhenReferenceIsNotDigest() {
        ImageId id = ImageId.fromRegistry(REGISTRY, REPO, "latest");

        assertEquals(REGISTRY, id.registry());
        assertEquals(REPO, id.name());
        assertEquals("latest", id.tag());
        assertNull(id.digest());
    }

    @Test
    void withDigestReturnsSameInstanceWhenBlank() {
        ImageId original = new ImageId(REGISTRY, REPO, "latest", null);
        assertSame(original, original.withDigest(null));
        assertSame(original, original.withDigest(""));
        assertSame(original, original.withDigest("   "));
    }
}

