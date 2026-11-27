package io.kestra.plugin.git.shared;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

/** Throwaway self-signed certificates for loopback TLS tests. */
final class TlsTestSupport {
    static final String PASSWORD = "changeit";

    private TlsTestSupport() {
    }

    static KeyStore selfSignedKeyStore(Path keystore, String san) throws Exception {
        var log = keystore.resolveSibling(keystore.getFileName() + ".log");
        var process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=" + san,
            "-validity", "2", "-storetype", "PKCS12",
            "-keystore", keystore.toString(), "-storepass", PASSWORD
        ).redirectErrorStream(true).redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            fail("keytool did not finish within 120s: " + Files.readString(log));
        }
        assertThat(Files.readString(log), process.exitValue(), is(0));

        var keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    static KeyStore selfSignedKeyStore(Path keystore) throws Exception {
        return selfSignedKeyStore(keystore, "dns:localhost,ip:127.0.0.1");
    }

    static String pem(KeyStore keyStore) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(keyStore.getCertificate("server").getEncoded())
            + "\n-----END CERTIFICATE-----\n";
    }
}
