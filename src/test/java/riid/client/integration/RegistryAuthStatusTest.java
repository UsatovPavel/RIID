package riid.client.integration;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

import riid.app.fs.HostFilesystem;
import riid.app.fs.NioHostFilesystem;
import riid.app.fs.TestPaths;

/**
 * Local registry:2 with htpasswd auth. Verifies 401/404/200 paths.
 */
@Tag("local")
@Testcontainers
@SuppressWarnings({"resource"})
class RegistryAuthStatusTest {

    private static final String USER = "testuser";
    private static final String PASSWORD = "password";
    private static String BCRYPT_HASH;
    private static final String REGISTRY_IMAGE = "registry:2";
    private static final int EXPECTED_HTPASSWD_PARTS = 2;
    private static final Path HTPASSWD_PATH;
    private static final HostFilesystem FS = new NioHostFilesystem();

    static {
        try {
            // generate bcrypt via lightweight container with htpasswd installed.
            // Java bcrypt вариант не подключён для экономии зависимостей.
            DockerImageName httpd = DockerImageName.parse("httpd:2-alpine");
            try (GenericContainer<?> gen = new GenericContainer<>(httpd).withCommand("sleep", "30")) {
                gen.start();
                org.testcontainers.containers.Container.ExecResult res =
                        gen.execInContainer("htpasswd", "-nbB", USER, PASSWORD);
                String output = res.getStdout().trim();
                String[] parts = output.split(":", EXPECTED_HTPASSWD_PARTS);
                if (parts.length != EXPECTED_HTPASSWD_PARTS) {
                    throw new IllegalStateException("Unexpected htpasswd output: " + output);
                }
                BCRYPT_HASH = parts[1];
            }

            HTPASSWD_PATH = TestPaths.tempFile(FS, TestPaths.DEFAULT_BASE_DIR, "htpasswd-", ".txt");
            FS.writeString(HTPASSWD_PATH, USER + ":" + BCRYPT_HASH);
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare htpasswd", e);
        }
    }

    @Container
    private static final GenericContainer<?> REGISTRY = new GenericContainer<>(REGISTRY_IMAGE)
            .withExposedPorts(5000)
            .withEnv("REGISTRY_HTTP_ADDR", "0.0.0.0:5000")
            .withEnv("REGISTRY_AUTH", "htpasswd")
            .withEnv("REGISTRY_AUTH_HTPASSWD_REALM", "Registry Realm")
            .withEnv("REGISTRY_AUTH_HTPASSWD_PATH", "/auth/htpasswd")
            .withCopyFileToContainer(MountableFile.forHostPath(HTPASSWD_PATH), "/auth/htpasswd");

    private static HttpClient CLIENT;
    private static String BASE_URL;
    private static String AUTH_HEADER;

    @BeforeAll
    static void start() throws Exception {
        REGISTRY.start();
        String host = REGISTRY.getHost();
        int port = REGISTRY.getMappedPort(5000);
        BASE_URL = "http://" + host + ":" + port;

        AUTH_HEADER = "Basic " + Base64.getEncoder()
                .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

        CLIENT = HttpClientFactory.create(new HttpClientConfig());
    }

    @AfterAll
    static void stop() throws Exception {
        if (CLIENT != null) {
            CLIENT.stop();
        }
    }

    @Test
    void unauthorizedPingReturns401() throws Exception {
        ContentResponse resp = CLIENT.newRequest(BASE_URL + "/v2/").send();
        assertEquals(401, resp.getStatus());
    }

    @Test
    void authorizedPingReturns200() throws Exception {
        int status = status("/v2/", true);
        assertEquals(200, status, "expected 200, got " + status);
    }

    @Test
    void missingManifestReturns404() throws Exception {
        int status = status("/v2/nonexistent-repo/manifests/latest", true);
        assertEquals(404, status, "expected 404, got " + status);
    }

    private int status(String path, boolean withAuth) throws Exception {
        Exception last = null;
        int attempts = 15;
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            try {
                var req = CLIENT.newRequest(BASE_URL + path);
                if (withAuth) {
                    req.headers(h -> h.put("Authorization", AUTH_HEADER));
                }
                int code = req.send().getStatus();
                seen.add(code);
                return code;
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        if (last != null) {
            throw new RuntimeException("Failed after attempts, seen=" + seen, last);
        }
        throw new IllegalStateException("Failed to get status for " + path);
    }
}


