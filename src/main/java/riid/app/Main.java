package riid.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.CacheAdapter;
import riid.cache.FileCacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.RegistryClientImpl;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple CLI bootstrap for demo purposes.
 */
public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String repo = RiidEnv.repo();
        String tag = RiidEnv.tag();
        String digest = RiidEnv.digest();

        String refForFetch = digest != null ? digest : tag;

        RegistryEndpoint endpoint = RegistryEndpoint.https("registry-1.docker.io");
        HttpClientConfig httpConfig = new HttpClientConfig();
        CacheAdapter cache = new FileCacheAdapter(resolveCacheDir());

        RegistryClientImpl client = new RegistryClientImpl(endpoint, httpConfig, cache);

        var manifestResult = client.fetchManifest(repo, refForFetch);
        LOGGER.info("Fetched manifest: {} ({})", manifestResult.digest(), manifestResult.mediaType());
        var manifest = manifestResult.manifest();
        var layers = manifest.layers();
        LOGGER.info("Layers count: {}", layers == null ? 0 : layers.size());
        if (layers != null && !layers.isEmpty()) {
            var first = layers.get(0);
            LOGGER.info("Fetching first layer: {}", first.digest());
            File tmp = File.createTempFile("riid-blob-", ".bin");
            var res = client.fetchBlob(new BlobRequest(repo, first.digest(), first.size(), first.mediaType()), tmp);
            LOGGER.info("Blob saved to: {}", res.path());
        }
        LOGGER.info("Done.");
    }

    private static String resolveCacheDir() throws Exception {
        String env = RiidEnv.cacheDir();
        if (env != null && !env.isBlank()) {
            return env;
        }
        Path tmp = Files.createTempDirectory("riid-cache");
        return tmp.toAbsolutePath().toString();
    }
}
