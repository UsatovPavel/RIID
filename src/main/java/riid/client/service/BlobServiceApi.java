package riid.client.service;

import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.BlobSink;
import riid.client.core.config.RegistryEndpoint;

import java.io.File;

/**
 * Blob service contract.
 */
public interface BlobServiceApi {
    BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, BlobSink sink, String scope);

    BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope);
}

