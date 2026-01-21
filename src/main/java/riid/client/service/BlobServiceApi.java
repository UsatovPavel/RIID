package riid.client.service;

import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.BlobSink;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;

import java.io.File;
import java.io.IOException;

/**
 * Blob service contract.
 */
public interface BlobServiceApi {
    BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, BlobSink sink, String scope);

    default BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope) {
        try (BlobSink sink = new riid.client.api.FileBlobSink(target)) {
            return fetchBlob(endpoint, req, sink, scope);
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob IO error"),
                    "Blob IO error",
                    e);
        } catch (Exception e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob sink error"),
                    "Blob sink error",
                    e);
        }
    }
}

