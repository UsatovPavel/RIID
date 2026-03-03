package riid.client.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import riid.client.core.config.AuthConfig;

/**
 * Factory for configured Jetty HttpClient.
 */
public final class HttpClientFactory {
    private static final String TLS_PREFIX = "SECURITY:TLS:";
    private static final String TLS_PROTOCOL = "TLS";
    private static final char[] EMPTY_PASSWORD = new char[0];

    private HttpClientFactory() {
    }

    public static HttpClient create(HttpClientConfig config) {
        return create(config, null);
    }

    public static HttpClient create(HttpClientConfig config, AuthConfig authConfig) {
        try {
            HttpClient client = createClient(authConfig);
            client.setConnectTimeout(config.connectTimeout().toMillis());
            client.setFollowRedirects(config.followRedirects());
            client.setMaxRedirects(config.maxRedirects());
            client.start();
            return client;
        } catch (Exception e) {
            throw new IllegalStateException(
                    tlsMessage("HTTP_CLIENT_INIT_FAILED", "failed to initialize Jetty HttpClient"),
                    e);
        }
    }

    private static HttpClient createClient(AuthConfig authConfig) throws GeneralSecurityException, IOException {
        SSLContext sslContext = buildSslContext(authConfig);
        if (sslContext == null) {
            return new HttpClient();
        }
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setSslContext(sslContext);
        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);
        return new HttpClient(new HttpClientTransportOverHTTP(connector));
    }

    private static SSLContext buildSslContext(AuthConfig authConfig) throws GeneralSecurityException, IOException {
        if (authConfig == null) {
            return null;
        }
        String caPath = authConfig.caPath();
        String certPath = authConfig.certPath();
        String keyPath = authConfig.keyPath();
        boolean hasCa = hasText(caPath);
        boolean hasCert = hasText(certPath);
        boolean hasKey = hasText(keyPath);
        if (!hasCa && !hasCert && !hasKey) {
            return null;
        }
        if (hasCert != hasKey) {
            throw new IllegalArgumentException(
                    tlsMessage("CERT_KEY_PAIR_INVALID", "certPath and keyPath must be set together for mTLS"));
        }
        KeyManagerFactory keyManagerFactory = hasCert ?
         createKeyManagerFactory(Path.of(certPath), Path.of(keyPath)) : null;
        TrustManagerFactory trustManagerFactory = hasCa ? createTrustManagerFactory(Path.of(caPath)) : null;
        SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
        sslContext.init(
                keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers(),
                trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(),
                new SecureRandom());
        return sslContext;
    }

    private static KeyManagerFactory createKeyManagerFactory(Path certPath, Path keyPath)
            throws GeneralSecurityException, IOException {
        List<X509Certificate> certificates = loadCertificates(certPath);
        PrivateKey privateKey = loadPrivateKey(keyPath);
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, EMPTY_PASSWORD);
        Certificate[] chain = certificates.toArray(Certificate[]::new);
        keyStore.setKeyEntry("riid-client", privateKey, EMPTY_PASSWORD, chain);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, EMPTY_PASSWORD);
        return keyManagerFactory;
    }

    private static TrustManagerFactory createTrustManagerFactory(Path caPath)
            throws GeneralSecurityException, IOException {
        List<X509Certificate> certificates = loadCertificates(caPath);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, EMPTY_PASSWORD);
        for (int i = 0; i < certificates.size(); i++) {
            trustStore.setCertificateEntry("riid-ca-" + i, certificates.get(i));
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        return trustManagerFactory;
    }

    private static List<X509Certificate> loadCertificates(Path certPath) throws GeneralSecurityException, IOException {
        try (var input = Files.newInputStream(certPath)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates = factory.generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException(
                        tlsMessage("CERT_INVALID", "no X.509 certificates found in " + certPath));
            }
            List<X509Certificate> x509 = new ArrayList<>(certificates.size());
            for (Certificate certificate : certificates) {
                x509.add((X509Certificate) certificate);
            }
            return x509;
        }
    }

    private static PrivateKey loadPrivateKey(Path keyPath) throws IOException {
        String pem = Files.readString(keyPath);
        String pkcs8 = extractPemBlock(pem, "PRIVATE KEY");
        if (pkcs8 == null) {
            if (extractPemBlock(pem, "RSA PRIVATE KEY") != null) {
                throw new IllegalArgumentException(
                        tlsMessage("KEY_INVALID", "PKCS#1 private keys are not supported; use PKCS#8"));
            }
            throw new IllegalArgumentException(
                    tlsMessage("KEY_INVALID", "no PKCS#8 PRIVATE KEY block found in " + keyPath));
        }
        byte[] encoded = Base64.getMimeDecoder().decode(pkcs8);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        List<String> algorithms = List.of("RSA", "EC", "DSA");
        for (String algorithm : algorithms) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (InvalidKeySpecException ignored) {
                // Try next algorithm until one matches.
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(
                        tlsMessage("KEY_FACTORY_INIT_FAILED", "failed to initialize key factory for " + algorithm),
                        e);
            }
        }
        throw new IllegalArgumentException(
                tlsMessage("KEY_ALGORITHM_UNSUPPORTED", "unsupported private key algorithm in " + keyPath));
    }

    private static String extractPemBlock(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int beginIndex = pem.indexOf(begin);
        if (beginIndex < 0) {
            return null;
        }
        int contentStart = beginIndex + begin.length();
        int endIndex = pem.indexOf(end, contentStart);
        if (endIndex < 0) {
            return null;
        }
        return pem.substring(contentStart, endIndex).replaceAll("\\s+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String tlsMessage(String kind, String details) {
        return TLS_PREFIX + kind + ": " + details;
    }
}

