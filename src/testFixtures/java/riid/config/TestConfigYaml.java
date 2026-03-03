package riid.core.config;

/**
 * Shared YAML snippets for integration tests.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestConfigYaml {
    public static final String CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDjTCCAnWgAwIBAgIEJA9kNDANBgkqhkiG9w0BAQsFADB3MQswCQYDVQQGEwJD
            TjESMBAGA1UECBMJR3Vhbmdkb25nMRIwEAYDVQQHEwlHdWFuZ3pob3UxFTATBgNV
            BAoTDExvY2FsIENsaWVudDEVMBMGA1UECxMMTG9jYWwgQ2xpZW50MRIwEAYDVQQD
            Ewlsb2NhbGhvc3QwHhcNMjAwMzI4MDkxODI5WhcNMjAwNjI2MDkxODI5WjB3MQsw
            CQYDVQQGEwJDTjESMBAGA1UECBMJR3Vhbmdkb25nMRIwEAYDVQQHEwlHdWFuZ3po
            b3UxFTATBgNVBAoTDExvY2FsIENsaWVudDEVMBMGA1UECxMMTG9jYWwgQ2xpZW50
            MRIwEAYDVQQDEwlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK
            AoIBAQCF0Wn9eOLmVGW3TrT2tcpy88HEz2w2s1KGFqSmKzCyb3DtQmTm/SGIsMyB
            Wn0yVIEOEayQzqPxvUVJD0cOOVn2KzWW1GYtf8VaBivFvJLz6kuzp4xYxlRSvjFf
            Ib7Ot1S/U45/q6VP+u/3AyWrc7ubr6LvMyo/JKqIozkOjYKKnHkBxl5g1SlHgTat
            0QWxtYnfblM1xWBJJsehj5M7fFcFWf7xh7Rli8LNnwBMIN9iOoPIEHezNwyhduA2
            VvSW3DaF5DQT0bwE6mREt4iPgmPn3bEh1lM7Xqn4aal3XxBUygUY4S+Gx9N2WnnR
            zkWmixSFcOLp97RSp8gX2Hw/MrWTAgMBAAGjITAfMB0GA1UdDgQWBBRPJlXzN8zq
            LQAGBHPrkOOHBq3NZzANBgkqhkiG9w0BAQsFAAOCAQEAYAIu+YwBA/AUzFeswD5w
            labYMnWiaF2CT9PjvViCc4RP0xObFxie/zJzevOXxrOYbtnJlOrnNwME+3aEdJwO
            t877d43yZcDFULy2EJvPbrCXxFelJVU+JEpglfia+32Md6LbqeFPmdJd85hdOciK
            emwDVlbeHlXGifarQ7gMDQufWYYf4j/BHnZYg9Vlc3SNZA00fwVvKcb9dhWJiI41
            r0xcFjchvCq7ySDdh2Pnx6CjrBsNYN0TzvCO2qcNizpFas78aoIZXiprlhsKAV1d
            xsdmFTWg0elCK10nEj04/m2wwUrKdmz4hasynt7P3ZkqcGKSuTZqwE6Jj7lM91hH
            PQ==
            -----END CERTIFICATE-----
            """;

    public static final String KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCF0Wn9eOLmVGW3
            TrT2tcpy88HEz2w2s1KGFqSmKzCyb3DtQmTm/SGIsMyBWn0yVIEOEayQzqPxvUVJ
            D0cOOVn2KzWW1GYtf8VaBivFvJLz6kuzp4xYxlRSvjFfIb7Ot1S/U45/q6VP+u/3
            AyWrc7ubr6LvMyo/JKqIozkOjYKKnHkBxl5g1SlHgTat0QWxtYnfblM1xWBJJseh
            j5M7fFcFWf7xh7Rli8LNnwBMIN9iOoPIEHezNwyhduA2VvSW3DaF5DQT0bwE6mRE
            t4iPgmPn3bEh1lM7Xqn4aal3XxBUygUY4S+Gx9N2WnnRzkWmixSFcOLp97RSp8gX
            2Hw/MrWTAgMBAAECggEAaQJgTgvi4iQwfZxB3afiajpmP+8VWAd4Jsj2A3+5Awz4
            bPbA/5J8EivLD2bW//vYGhY8eJXviO+hwyc99yeCNO7LasTaObIeR/q3tr+4AbhG
            F4DPXYqk+RlO3Pw6IzUyR8Fl/UHQw+aDEC9kDBsRiaZWEabw7AP13qtXRtg9LE9i
            NsH2kd1buX8ozJ740ZA9DcW3rR22Zu+LiCRc6gqydlPQGHecAeZGgIuMN/jG5+dB
            P15RumX8IbI9dUVGhASHdT5mUONIp6C1kQCZjPlg9PAGA8jcWKJODBCRO5ffiPRV
            B7lD5a6ZZRXIEDVRZRmAnD2K7YVeaLZWsjzjrVTJgQKBgQDIGePR3tIPDz2rTIBS
            B6a9x5rI7kthfKmRzPkv0Bqoff8j3pRwdoyWRp5sCbujmK7JG8n2S/cL8cV++cN3
            1rESqEBtH+Q9qoWs/jaq/7SH7HyfZQc8vd5Dn92ntdBSaxOUXOepLkvmsE+oH9kL
            SC/+wP3XdwGnFloMEp0Jcj7xgwKBgQCrM1P/POFdXdJ5+o3Si0oetxA5Acer+/zj
            rYWeJVr4GJOrrwRJhWpf4nHe7eME3lKO2t69+FNN2CQSxLxyK9qPwaezrqXxyNW0
            7/AjH8S13DFyVA+erSLc5/SI7u4U5sQ1iaCfYSIR1IWbSZiDSrBbVDLQnaEwoKct
            2cSReRA+sQKBgB64AsZ8S4kuUMxUYTq2m/10gCmqk33y0mFksziI3R7SwPFzwRwG
            xwxm6PkzAVBbJzIOhxG9lb6KrkQQ+dYS6novxDw8ciCZZg4ptKDJwqA/SN57dwH3
            MAD3sOKHQeT1NTtIi/Pn/JT8qi3oPbzCp0OXwyBpz6IkB0zlKqCBnnIbAoGAc0WR
            Nhdo6vPER0tT+MK+umWqb6fqKLv3r9ljUXN3h/sMWxnxugsx77PJ1j+4jsufLP7j
            4Wd1t8FjsJt1Ay7R79+Fqm9a3qzKcBTqTMwUKBtF0+QTzFRpV/J6bUTrW3lx/VZY
            p1fAl97PwxpPrX85tZTAzkSEhvh0+GNbflPUg1ECgYBTI0lT1HlcXL7T2XR6WCOR
            s/F08hiApegNL0eDaljBx+VwTbybxemYx5BY+3rmWFfHh3MmG7hiHp1+0x75lEe2
            3j8sfFdyB/JhCzSFWl/8YSanaKmtK/ZdFp1n/Jg+5ei/SzjDtGIfJ6BSnwslmNi1
            QOFXgmJGT8oYsPquKbaDzg==
            -----END PRIVATE KEY-----
            """;

    private TestConfigYaml() {
    }

    public static String minimalDockerHubConfigWithEmptyAuth(int maxConcurrentRegistry) {
        String scheme = TestRegistryConfig.scheme();
        String host = TestRegistryConfig.host();
        int port = TestRegistryConfig.port();
        return """
                client:
                  http:
                    backoffExponentBase: 2
                  auth: {}
                  registries:
                    - scheme: %s
                      host: %s
                      port: %d
                dispatcher:
                  maxConcurrentRegistry: %d
                """.formatted(scheme, host, port, maxConcurrentRegistry);
    }

    public static String dockerHubConfigWithRuntimeTempDir(int maxConcurrentRegistry, String tempDirectory) {
        String scheme = TestRegistryConfig.scheme();
        String host = TestRegistryConfig.host();
        int port = TestRegistryConfig.port();
        return """
                client:
                  http:
                    connectTimeout: PT5S
                    requestTimeout: PT10S
                    maxRetries: 2
                    retryIdempotentOnly: true
                    followRedirects: true
                    initialBackoff: PT0.2S
                    maxBackoff: PT2S
                    backoffExponentBase: 2
                  auth:
                    defaultTokenTtlSeconds: 600
                  registries:
                    - scheme: %s
                      host: %s
                      port: %d
                dispatcher:
                  maxConcurrentRegistry: %d
                app:
                  tempDirectory: "%s"
                """.formatted(scheme, host, port, maxConcurrentRegistry, tempDirectory);
    }
}
