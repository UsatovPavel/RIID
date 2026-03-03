package riid.client.api;

import org.junit.jupiter.api.Test;
import riid.client.core.error.ClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobRequestTest {
    private static final String REPO = "repo";
    private static final String DIGEST = "sha256:a";
    private static final String MEDIA_TYPE = "application/octet-stream";

    @Test
    void rangeHeaderForBoundedRange() {
        BlobRequest req = new BlobRequest(REPO, DIGEST, 10L, MEDIA_TYPE,
                new BlobRequest.RangeSpec.Bounded(2L, 5L));
        assertEquals("bytes=2-5", req.rangeHeaderValue());
    }

    @Test
    void rangeHeaderForOpenEndedRange() {
        BlobRequest req = new BlobRequest(REPO, DIGEST, 10L, MEDIA_TYPE,
                new BlobRequest.RangeSpec.From(2L));
        assertEquals("bytes=2-", req.rangeHeaderValue());
    }

    @Test
    void rangeHeaderForSuffixRange() {
        BlobRequest req = new BlobRequest(REPO, DIGEST, 10L, MEDIA_TYPE,
                new BlobRequest.RangeSpec.Suffix(10L));
        assertEquals("bytes=-10", req.rangeHeaderValue());
    }

    @Test
    void rangeHeaderIsNullWhenRangeNotProvided() {
        BlobRequest req = new BlobRequest(
                REPO,
                DIGEST,
                10L,
                MEDIA_TYPE,
                new BlobRequest.RangeSpec.All());
        assertNull(req.rangeHeaderValue());
    }

    @Test
    void blobRequestRejectsNullRangeSpec() {
        var ex = assertThrows(
                NullPointerException.class,
                () -> new BlobRequest(REPO, DIGEST, 10L, MEDIA_TYPE, null));
        assertNotNull(ex.getMessage());
    }

    @Test
    void boundedRangeRejectsInvertedBounds() {
        var ex = assertThrows(ClientException.class, () -> new BlobRequest.RangeSpec.Bounded(5L, 2L));
        assertNotNull(ex.getMessage());
    }
}
