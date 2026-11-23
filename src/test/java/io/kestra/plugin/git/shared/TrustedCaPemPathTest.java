package io.kestra.plugin.git.shared;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;
import io.kestra.plugin.git.shared.TestTasks.TestKestraTask;
import io.kestra.sdk.KestraClient;
import io.kestra.sdk.internal.ApiException;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/** {@code trustedCaPemPath} must apply to the Kestra API client, not only to the JVM-global Git transport. */
@KestraTest
class TrustedCaPemPathTest {
    private static final char[] PASSWORD = "changeit".toCharArray();

    private static HttpsServer server;
    private static String url;
    private static String pem;

    @TempDir
    Path tempDir;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void startServer(@TempDir Path dir) throws Exception {
        var keyStore = selfSignedKeyStore(dir.resolve("server.p12"));
        pem = toPem(keyStore.getCertificate("server").getEncoded());

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD);
        var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        // Literal IP: where localhost also resolves to ::1 (CI runners), httpclient retries a failed handshake on
        // the next address and reports that connect error instead of the SSLHandshakeException.
        url = "https://127.0.0.1:" + server.getAddress().getPort();
    }

    private static KeyStore selfSignedKeyStore(Path keystore) throws Exception {
        var log = keystore.resolveSibling(keystore.getFileName() + ".log");
        var process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-validity", "2", "-storetype", "PKCS12",
            "-keystore", keystore.toString(), "-storepass", new String(PASSWORD)
        ).redirectErrorStream(true).redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            fail("keytool did not finish within 120s: " + Files.readString(log));
        }
        assertThat(Files.readString(log), process.exitValue(), is(0));

        var keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keyStore.load(in, PASSWORD);
        }
        return keyStore;
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static String toPem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
            + "\n-----END CERTIFICATE-----\n";
    }

    private Path writePem(String name, String content) throws Exception {
        return Files.writeString(tempDir.resolve(name), content);
    }

    private static TestCloningTask.TestCloningTaskBuilder<?, ?> task(Property<String> trustedCaPemPath) {
        return TestCloningTask.builder()
            .id("clone-" + IdUtils.create())
            .type(TestCloningTask.class.getName())
            .url(Property.ofValue("https://github.com/kestra-io/plugin-git"))
            .kestraUrl(Property.ofValue(url))
            .auth(AbstractCloningTask.Auth.builder().auto(Property.ofValue(false)).build())
            .trustedCaPemPath(trustedCaPemPath);
    }

    private RunContext runContext(Task task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    /** The stub answers 404 to everything, so a 404 proves the TLS handshake passed and the server was reached. */
    private static void assertReachedServer(KestraClient client) {
        var e = assertThrows(ApiException.class, () -> client.misc().configuration());
        assertThat(e.getCode(), is(404));
    }

    private static void assertHandshakeFails(KestraClient client) {
        var failure = assertThrows(Throwable.class, () -> client.misc().configuration());
        assertThat("failure: " + failure, hasHandshakeFailure(failure), is(true));
    }

    private static boolean hasHandshakeFailure(Throwable failure) {
        for (var t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    @Test
    void cloningTask_reachesAnApiSignedByTheTrustedCa() throws Exception {
        var pemPath = writePem("ca.pem", pem);
        var task = task(Property.ofValue(pemPath.toString())).build();

        assertReachedServer(task.kestraClient(runContext(task)));
    }

    @Test
    void kestraApiTask_reachesAnApiSignedByTheTrustedCa() throws Exception {
        var pemPath = writePem("ca-kestra.pem", pem);
        var task = TestKestraTask.builder()
            .id("kestra-" + IdUtils.create())
            .type(TestKestraTask.class.getName())
            .url(Property.ofValue("https://github.com/kestra-io/plugin-git"))
            .kestraUrl(Property.ofValue(url))
            .auth(TestKestraTask.Auth.builder().apiToken(Property.ofValue("token")).build())
            .trustedCaPemPath(Property.ofValue(pemPath.toString()))
            .build();

        assertReachedServer(task.kestraClient(runContext(task)));
    }

    @Test
    void cloningTask_failsTheHandshakeWithoutThePem() throws Exception {
        var task = task(null).build();

        assertHandshakeFails(task.kestraClient(runContext(task)));
    }

    @Test
    void helper_isEmptyWhenUnsetOrBlank() throws Exception {
        var unset = task(null).build();
        var blank = task(Property.ofValue("   ")).build();

        assertThat(unset.trustedCaSslContext(runContext(unset)).isEmpty(), is(true));
        assertThat(blank.trustedCaSslContext(runContext(blank)).isEmpty(), is(true));
    }

    @Test
    void helper_namesThePathWhenTheFileIsMissing() {
        var missing = tempDir.resolve("missing.pem").toString();
        var task = task(Property.ofValue(missing)).build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.trustedCaSslContext(runContext(task)));

        assertThat(e.getMessage(), containsString("'" + missing + "': file not found. Check that"));
    }

    @Test
    void helper_rejectsAFileWithoutCertificate() throws Exception {
        var notACert = writePem("not-a-cert.pem", "this is not a certificate\n").toString();
        var task = task(Property.ofValue(notACert)).build();

        var e = assertThrows(IllegalArgumentException.class, () -> task.trustedCaSslContext(runContext(task)));

        assertThat(e.getMessage(), containsString(notACert));
    }

    @Test
    void multiCertificatePem_trustsEveryCertificate() throws Exception {
        var other = toPem(selfSignedKeyStore(tempDir.resolve("other.p12")).getCertificate("server").getEncoded());
        var bundle = writePem("bundle.pem", other + pem);
        var task = task(Property.ofValue(bundle.toString())).build();

        // The served certificate is the second one of the bundle.
        assertReachedServer(task.kestraClient(runContext(task)));
    }
}
