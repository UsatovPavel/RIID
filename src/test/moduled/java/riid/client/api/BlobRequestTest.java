package riid.client.api;

import org.junit.jupiter.api.Test;
import riid.client.core.error.ClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobRequestTest {

    @Test
    void rangeHeaderForBoundedRange() {
        BlobRequest req = new BlobRequest("repo", "sha256:a", 10L, "application/octet-stream",
                new BlobRequest.RangeSpec(2L, 5L));
        assertEquals("bytes=2-5", req.rangeHeaderValue());
    }

    @Test
    void rangeHeaderForOpenEndedRange() {
        BlobRequest req = new BlobRequest("repo", "sha256:a", 10L, "application/octet-stream",
                new BlobRequest.RangeSpec(2L, null));
        assertEquals("bytes=2-", req.rangeHeaderValue());
    }

    @Test
    void rangeHeaderForSuffixRange() {
        BlobRequest req = new BlobRequest("repo", "sha256:a", 10L, "application/octet-stream",
                new BlobRequest.RangeSpec(null, 10L));
        assertEquals("bytes=-10", req.rangeHeaderValue());
    }

    @Test
    void rangeHeaderIsNullWhenRangeNotProvided() {
        BlobRequest req = new BlobRequest("repo", "sha256:a", 10L, "application/octet-stream");
        assertNull(req.rangeHeaderValue());
    }

    @Test
    void rangeSpecRejectsBothBoundsNull() {
        var ex = assertThrows(ClientException.class, () -> new BlobRequest.RangeSpec(null, null));
        assertNotNull(ex.getMessage());
    }
}
