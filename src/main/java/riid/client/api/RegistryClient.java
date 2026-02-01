package riid.client.api;

import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.TagList;

import java.io.File;
import java.util.Optional;

/**
 * High-level client interface.
 */
public interface RegistryClient extends AutoCloseable {
    ManifestResult fetchManifest(String repository, String reference);

    BlobResult fetchConfig(String repository, Manifest manifest, File target);

    BlobResult fetchBlob(BlobRequest request, File target);

    Optional<Long> headBlob(String repository, String digest);

    TagList listTags(String repository, Integer n, String last);

    @Override // throw Exception because impl that throws exception. Don't change
    void close() throws Exception;

}

