package riid.cache.oci;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import riid.core.model.manifest.TestManifests;

class CacheRecordTest {
    @Test
    void cancelEvictionRejectsAStaleClaim() {
        ImageDigest digest = ImageDigest.parse(TestManifests.digest('a'));
        CacheRecord record = new CacheRecord(digest);
        CacheEntry entry = new CacheEntry(digest, 1, CacheMediaType.OCI_LAYER, digest.toString());
        try (CacheLease ignored = record.publishAndAcquire(entry, Path.of("cache-entry"), 1)) {
            // Release the initial reader before claiming the record for eviction.
        }
        CacheRecord.EvictionClaim claim = record.tryStartEviction();
        record.cancelEviction(claim);

        assertThrows(IllegalStateException.class, () -> record.cancelEviction(claim));
    }
}
