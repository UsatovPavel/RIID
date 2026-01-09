package riid.app;

import riid.cache.CacheAdapter;
import riid.client.api.RegistryClientImpl;
import riid.client.api.BlobRequest;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;

import java.io.File;

public final class Main {
    public static void main(String[] args) throws Exception {
        String repo = System.getenv().getOrDefault("RIID_REPO", "library/busybox");
        String ref = System.getenv().getOrDefault("RIID_REF", "latest");

        RegistryEndpoint endpoint = RegistryEndpoint.https("registry-1.docker.io");
        HttpClientConfig httpConfig = new HttpClientConfig();
        CacheAdapter cache = new riid.cache.FileCacheAdapter("/var/cache/riid");

        RegistryClientImpl client = new RegistryClientImpl(endpoint, httpConfig, cache);

        var manifestResult = client.fetchManifest(repo, ref);
        System.out.println("Fetched manifest: " + manifestResult.digest() + " (" + manifestResult.mediaType() + ")");
        var manifest = manifestResult.manifest();
        var layers = manifest.layers();
        System.out.println("Layers count: " + (layers == null ? 0 : layers.size()));
        if (layers != null && !layers.isEmpty()) {
            var first = layers.get(0);
            System.out.println("Fetching first layer: " + first.digest());
            File tmp = File.createTempFile("riid-blob-", ".bin");
            var res = client.fetchBlob(new BlobRequest(repo, first.digest(), first.size(), first.mediaType()), tmp);
            System.out.println("Blob saved to: " + res.path());
        }
        System.out.println("Done.");
    }
}
