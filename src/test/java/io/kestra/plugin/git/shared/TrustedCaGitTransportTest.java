package io.kestra.plugin.git.shared;

import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.stream.Stream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.eclipse.jgit.transport.http.apache.HttpClientConnection;
import org.eclipse.jgit.transport.http.apache.HttpClientConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;
import io.kestra.plugin.git.shared.services.SshTransportConfigCallback;
import io.kestra.plugin.git.shared.services.TaskTransportConfigCallback;
import io.kestra.plugin.git.shared.services.TrustedCaHttpConnectionFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code trustedCaPemPath} must apply to the Git transport of the task that sets it, and to nothing else.
 * Re-run on every JGit bump: it relies on how {@code TransportHttp} and {@code HttpClientConnection} pick their
 * SSL socket factory.
 */
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class TrustedCaGitTransportTest {
    private static final String REPO_PATH = "/repo.git";

    @TempDir
    Path tempDir;

    @Inject
    private RunContextFactory runContextFactory;

    private org.eclipse.jgit.transport.http.HttpConnectionFactory previousFactory;
    private GitServer server;

    @BeforeEach
    void rememberGlobalFactory() {
        previousFactory = HttpTransport.getConnectionFactory();
    }

    @AfterEach
    void restoreGlobalFactory() throws Exception {
        HttpTransport.setConnectionFactory(previousFactory);
        if (server != null) {
            server.close();
        }
    }

    static Stream<org.eclipse.jgit.transport.http.HttpConnectionFactory> factories() {
        return Stream.of(new JDKHttpConnectionFactory(), new HttpClientConnectionFactory());
    }

    private String pemOf(KeyStore keyStore, String name) throws Exception {
        return Files.writeString(tempDir.resolve(name), TlsTestSupport.pem(keyStore)).toString();
    }

    private GitServer serve(KeyStore keyStore) throws Exception {
        server = new GitServer(tempDir.resolve("remote"), keyStore);
        return server;
    }

    private TestCloningTask task(String pemPath) {
        return TestCloningTask.builder()
            .id("clone-" + IdUtils.create())
            .type(TestCloningTask.class.getName())
            .url(Property.ofValue("https://unused"))
            .auth(AbstractCloningTask.Auth.builder().auto(Property.ofValue(false)).build())
            .trustedCaPemPath(pemPath == null ? null : Property.ofValue(pemPath))
            .build();
    }

    private RunContext runContext(TestCloningTask task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    private int lsRemote(TestCloningTask task, String url) throws Exception {
        var command = Git.lsRemoteRepository().setRemote(url);
        return task.authentified(command, runContext(task)).call().size();
    }

    private void clone(TestCloningTask task, String url, String dir) throws Exception {
        var command = Git.cloneRepository().setURI(url).setDirectory(tempDir.resolve(dir).toFile());
        try (var git = task.authentified(command, runContext(task)).call()) {
            assertTrue(Files.exists(tempDir.resolve(dir).resolve("file.txt")));
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    void lsRemoteAndClone_succeedWithTheTrustedCa(org.eclipse.jgit.transport.http.HttpConnectionFactory factory) throws Exception {
        HttpTransport.setConnectionFactory(factory);
        var keyStore = TlsTestSupport.selfSignedKeyStore(tempDir.resolve("server.p12"));
        var url = serve(keyStore).url();
        var task = task(pemOf(keyStore, "ca.pem"));

        assertThat(lsRemote(task, url) > 0, is(true));
        clone(task, url, "clone");
    }

    @ParameterizedTest
    @MethodSource("factories")
    void lsRemoteAndClone_failTheHandshakeWithoutThePem(org.eclipse.jgit.transport.http.HttpConnectionFactory factory) throws Exception {
        HttpTransport.setConnectionFactory(factory);
        var url = serve(TlsTestSupport.selfSignedKeyStore(tempDir.resolve("server.p12"))).url();
        var task = task(null);

        var failure = assertThrows(TransportException.class, () -> lsRemote(task, url));
        assertThat(failure.toString(), hasCause(failure, SSLException.class), is(true));
        assertThrows(TransportException.class, () -> clone(task, url, "clone"));
    }

    @ParameterizedTest
    @MethodSource("factories")
    void theCaIsNotTrustedByALaterTaskThatDoesNotSetIt(org.eclipse.jgit.transport.http.HttpConnectionFactory factory) throws Exception {
        HttpTransport.setConnectionFactory(factory);
        var keyStore = TlsTestSupport.selfSignedKeyStore(tempDir.resolve("server.p12"));
        var url = serve(keyStore).url();
        var defaultContext = SSLContext.getDefault();
        var defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();

        assertThat(lsRemote(task(pemOf(keyStore, "ca.pem")), url) > 0, is(true));

        assertThrows(TransportException.class, () -> lsRemote(task(null), url));
        assertThat(SSLContext.getDefault(), is(sameInstance(defaultContext)));
        assertThat(HttpsURLConnection.getDefaultSSLSocketFactory(), is(sameInstance(defaultSocketFactory)));
    }

    @ParameterizedTest
    @MethodSource("factories")
    void hostnameVerificationStaysStrict(org.eclipse.jgit.transport.http.HttpConnectionFactory factory) throws Exception {
        HttpTransport.setConnectionFactory(factory);
        var keyStore = TlsTestSupport.selfSignedKeyStore(tempDir.resolve("server.p12"), "dns:other.example");
        var url = serve(keyStore).url();
        var task = task(pemOf(keyStore, "ca.pem"));

        // The CA is trusted, but the certificate does not name 127.0.0.1
        assertThrows(TransportException.class, () -> lsRemote(task, url));
    }

    @Test
    void wrappedConnectionKeepsTheConfiguredHttpTransport() throws Exception {
        var keyStore = TlsTestSupport.selfSignedKeyStore(tempDir.resolve("server.p12"));
        var task = TestCloningTask.builder()
            .id("clone-" + IdUtils.create())
            .type(TestCloningTask.class.getName())
            .url(Property.ofValue("https://unused"))
            .auth(AbstractCloningTask.Auth.builder().auto(Property.ofValue(false)).build())
            .trustedCaPemPath(Property.ofValue(pemOf(keyStore, "ca.pem")))
            .noProxy(Property.ofValue(true))
            .build();
        var runContext = runContext(task);
        task.configureHttpTransport(runContext);

        var wrapped = new TrustedCaHttpConnectionFactory(HttpTransport.getConnectionFactory(), task.trustedCaTrustManagers(runContext).orElseThrow());
        HttpConnection connection = wrapped.create(new URL("https://127.0.0.1:1/"));
        assertThat(connection, instanceOf(HttpClientConnection.class));
        assertThat(connection.usingProxy(), is(false));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (var t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void compositeCallback_appliesEachSettingToItsOwnTransportType() throws Exception {
        var trustManagers = new TrustManager[0];
        var ssh = new SshTransportConfigCallback("key".getBytes(), null, false, null);
        var callback = new TaskTransportConfigCallback(ssh, trustManagers);

        try (var git = Git.init().setDirectory(tempDir.resolve("local").toFile()).call()) {
            Repository repository = git.getRepository();
            try (var sshTransport = Transport.open(repository, new URIish("ssh://git@127.0.0.1/repo.git"));
                var httpTransport = Transport.open(repository, new URIish("https://127.0.0.1/repo.git"))) {
                var defaultSshFactory = ((SshTransport) sshTransport).getSshSessionFactory();

                callback.configure(sshTransport);
                callback.configure(httpTransport);

                assertThat(((SshTransport) sshTransport).getSshSessionFactory(), is(not(sameInstance(defaultSshFactory))));
                assertThat(((TransportHttp) httpTransport).getHttpConnectionFactory(), instanceOf(TrustedCaHttpConnectionFactory.class));
            }
        }
    }

    @Test
    void compositeCallback_leavesAnSshTransportAloneWithoutSshSettings() throws Exception {
        var callback = new TaskTransportConfigCallback(null, new TrustManager[0]);

        try (var git = Git.init().setDirectory(tempDir.resolve("local").toFile()).call();
            var sshTransport = Transport.open(git.getRepository(), new URIish("ssh://git@127.0.0.1/repo.git"))) {
            var defaultSshFactory = ((SshTransport) sshTransport).getSshSessionFactory();

            callback.configure(sshTransport);

            assertThat(((SshTransport) sshTransport).getSshSessionFactory(), is(sameInstance(defaultSshFactory)));
        }
    }

    /** Smart-HTTP Git server over TLS on the loopback interface, serving one repository holding one commit. */
    static final class GitServer implements AutoCloseable {
        private final Server jetty = new Server();
        private final Git git;
        private final int port;

        GitServer(Path dir, KeyStore keyStore) throws Exception {
            git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
            Files.writeString(dir.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();

            var ssl = new SslContextFactory.Server();
            ssl.setKeyStore(keyStore);
            ssl.setKeyStorePassword(TlsTestSupport.PASSWORD);
            ssl.setSniRequired(false);
            var connector = new ServerConnector(jetty, new SslConnectionFactory(ssl, "http/1.1"), new HttpConnectionFactory());
            connector.setHost(InetAddress.getLoopbackAddress().getHostAddress());
            connector.setPort(0);
            jetty.addConnector(connector);

            var context = new ServletContextHandler();
            context.setContextPath("/");
            var servlet = new GitServlet();
            servlet.setRepositoryResolver((req, name) -> {
                git.getRepository().incrementOpen();
                return git.getRepository();
            });
            context.addServlet(new ServletHolder(servlet), "/*");
            jetty.setHandler(context);
            jetty.start();
            port = connector.getLocalPort();
        }

        String url() {
            return "https://127.0.0.1:" + port + REPO_PATH;
        }

        @Override
        public void close() throws Exception {
            jetty.stop();
            git.close();
        }
    }
}
