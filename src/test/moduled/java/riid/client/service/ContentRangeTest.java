package riid.client.service;

import org.junit.jupiter.api.Test;
import riid.client.api.BlobRequest;
import riid.client.core.error.ClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentRangeTest {

    @Test
    void parseValidFullRange() {
        ContentRange parsed = ContentRange.parse("bytes 0-99/100");
        assertEquals(0L, parsed.start());
        assertEquals(99L, parsed.end());
        assertEquals(100L, parsed.totalSize());
        assertEquals(100L, parsed.length());
        assertTrue(parsed.coversFull());
    }

    @Test
    void parseValidWildcardTotal() {
        ContentRange parsed = ContentRange.parse("bytes 0-9/*");
        assertEquals(0L, parsed.start());
        assertEquals(9L, parsed.end());
        assertNull(parsed.totalSize());
        assertFalse(parsed.coversFull());
    }

    @Test
    void parseRejectsInvalidUnit() {
        var ex = assertThrows(ClientException.class, () -> ContentRange.parse("items 0-99/100"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void parseRejectsEndBeforeStart() {
        var ex = assertThrows(ClientException.class, () -> ContentRange.parse("bytes 10-1/100"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void parseRejectsNonPositiveTotal() {
        var ex = assertThrows(ClientException.class, () -> ContentRange.parse("bytes 0-0/0"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void validateAgainstSuffixRangePassesWhenExpected() {
        ContentRange parsed = ContentRange.parse("bytes 90-99/100");
        parsed.validateAgainst(new BlobRequest.RangeSpec.Suffix(10L));
    }

    @Test
    void validateAgainstSuffixRangeFailsOnMismatch() {
        ContentRange parsed = ContentRange.parse("bytes 80-99/100");
        var ex = assertThrows(ClientException.class,
                () -> parsed.validateAgainst(new BlobRequest.RangeSpec.Suffix(10L)));
        assertNotNull(ex.getMessage());
    }
}
