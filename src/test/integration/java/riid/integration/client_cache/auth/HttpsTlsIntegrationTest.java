package riid.integration.client_cache.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import riid.client.core.config.AuthConfig;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.core.config.TestConfigYaml;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.core.fs.TestPaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
class HttpsTlsIntegrationTest {
    private static final char[] EMPTY_PASSWORD = new char[0];

    private final HostFilesystem fs = new NioHostFilesystem();
    private HttpsServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void httpsWithTrustedCustomCaSucceeds() throws Exception {
        Path certPath = writePem("https-cert-", TestConfigYaml.CERT_PEM);
        Path keyPath = writePem("https-key-", TestConfigYaml.KEY_PEM);
        startHttpsServer(certPath, keyPath, false);

        Path caPath = writePem("https-ca-", TestConfigYaml.CERT_PEM);
        AuthConfig auth = new AuthConfig(300, null, null, caPath.toString());
        HttpClientConfig cfg = httpConfig();
        HttpClient client = HttpClientFactory.create(cfg, auth);
        try {
            HttpExecutor executor = new HttpExecutor(client, cfg);
            var resp = executor.get(uri("/ok"), Map.of());
            assertEquals(200, resp.statusCode());
            String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("ok", body);
        } finally {
            client.stop();
        }
    }

    @Test
    void invalidCaFailsFast() throws Exception {
        Path invalidCa = writePem("invalid-ca-", "not-a-certificate");
        AuthConfig auth = new AuthConfig(300, null, null, invalidCa.toString());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> HttpClientFactory.create(httpConfig(), auth));
        assertTrue(ex.getMessage().contains("SECURITY:TLS:HTTP_CLIENT_INIT_FAILED"));
        assertTrue(ex.getCause() instanceof CertificateException);
    }

    @Test
    void mtlsPositiveAndNegativeScenarios() throws Exception {
        Path certPath = writePem("mtls-cert-", TestConfigYaml.CERT_PEM);
        Path keyPath = writePem("mtls-key-", TestConfigYaml.KEY_PEM);
        startHttpsServer(certPath, keyPath, true);

        HttpClientConfig cfg = httpConfig();
        Path caPath = writePem("mtls-ca-", TestConfigYaml.CERT_PEM);

        AuthConfig positive = new AuthConfig(
                300,
                certPath.toString(),
                keyPath.toString(),
                caPath.toString());
        HttpClient clientWithCert = HttpClientFactory.create(cfg, positive);
        try {
            HttpExecutor executor = new HttpExecutor(clientWithCert, cfg);
            var resp = executor.get(uri("/mtls"), Map.of());
            assertEquals(200, resp.statusCode());
        } finally {
            clientWithCert.stop();
        }

        AuthConfig negative = new AuthConfig(300, null, null, caPath.toString());
        HttpClient clientWithoutCert = HttpClientFactory.create(cfg, negative);
        try {
            HttpExecutor executor = new HttpExecutor(clientWithoutCert, cfg);
            assertThrows(UncheckedIOException.class, () -> executor.get(uri("/mtls"), Map.of()));
        } finally {
            clientWithoutCert.stop();
        }
    }

    private Path writePem(String prefix, String content) throws IOException {
        Path path = TestPaths.tempFile(fs, TestPaths.DEFAULT_BASE_DIR, prefix, ".pem");
        fs.writeString(path, content);
        return path;
    }

    private void startHttpsServer(Path certPath, Path keyPath, boolean needClientAuth) throws Exception {
        SSLContext serverContext = buildServerSslContext(certPath, keyPath, certPath);
        server = HttpsServer.create(new InetSocketAddress(0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLContext context = getSSLContext();
                SSLParameters sslParameters = context.getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(needClientAuth);
                params.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    private static SSLContext buildServerSslContext(Path certPath, Path keyPath, Path trustCertPath)
            throws GeneralSecurityException, IOException {
        Certificate cert = loadCertificate(certPath);
        PrivateKey privateKey = loadPkcs8Key(keyPath);

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, EMPTY_PASSWORD);
        keyStore.setKeyEntry("server", privateKey, EMPTY_PASSWORD, new Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, EMPTY_PASSWORD);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, EMPTY_PASSWORD);
        trustStore.setCertificateEntry("client", loadCertificate(trustCertPath));

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return sslContext;
    }

    private static Certificate loadCertificate(Path path) throws GeneralSecurityException, IOException {
        try (var input = java.nio.file.Files.newInputStream(path)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey loadPkcs8Key(Path path) throws GeneralSecurityException, IOException {
        String pem = java.nio.file.Files.readString(path);
        String content = extractPemBlock(pem, "PRIVATE KEY");
        byte[] encoded = Base64.getMimeDecoder().decode(content);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static String extractPemBlock(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int beginIndex = pem.indexOf(begin);
        int endIndex = pem.indexOf(end);
        return pem.substring(beginIndex + begin.length(), endIndex).replaceAll("\\s+", "");
    }

    private URI uri(String path) {
        return URI.create("https://localhost:" + server.getAddress().getPort() + path);
    }

    private static HttpClientConfig httpConfig() {
        return HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .initialBackoff(Duration.ofMillis(50))
                .maxBackoff(Duration.ofMillis(50))
                .retryIdempotentOnly(true)
                .userAgent("riid-test")
                .followRedirects(true)
                .build();
    }
}
