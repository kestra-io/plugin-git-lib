package io.kestra.plugin.git.shared.services;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Servers such as Azure DevOps no longer accept SHA-1 {@code ssh-rsa} signatures, only {@code rsa-sha2-256/512}.
 * The legacy {@code com.jcraft:jsch:0.1.55} cannot negotiate those and fails with "Algorithm negotiation fail"
 * (kestra-io/plugin-git#377). This server mimics that restriction for both the host key and user authentication.
 */
class SshTransportConfigCallbackTest {

    private SshServer server;
    private KeyPair userKey;

    @BeforeEach
    void startServer() throws Exception {
        userKey = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        PublicKey authorizedKey = new ByteArrayBuffer(userKey.getPublicKeyBlob()).getRawPublicKey();

        Path hostKey = Files.createTempDirectory("ssh-host-key-").resolve("host.key");
        SimpleGeneratorHostKeyProvider hostKeyProvider = new SimpleGeneratorHostKeyProvider(hostKey);
        hostKeyProvider.setAlgorithm(KeyUtils.RSA_ALGORITHM);

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(hostKeyProvider);
        server.setSignatureFactories(List.of(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256));
        server.setPublickeyAuthenticator((username, key, session) -> KeyUtils.compareKeys(authorizedKey, key));
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop(true);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "s3cr3t")
    void connectsWithRsaKeyToServerRequiringRsaSha2(String passphrase) throws Exception {
        ByteArrayOutputStream privateKey = new ByteArrayOutputStream();
        if (passphrase == null) {
            userKey.writePrivateKey(privateKey);
        } else {
            userKey.writePrivateKey(privateKey, passphrase.getBytes(StandardCharsets.UTF_8));
        }

        var callback = new SshTransportConfigCallback(privateKey.toByteArray(), passphrase, false, null);
        URIish uri = new URIish("ssh://git@127.0.0.1:" + server.getPort() + "/repo.git");

        try (var repository = new InMemoryRepository(new DfsRepositoryDescription("test"));
             var transport = (SshTransport) Transport.open(repository, uri)) {
            callback.configure(transport);

            RemoteSession session = transport.getSshSessionFactory().getSession(uri, null, FS.DETECTED, 10_000);
            try {
                assertThat(server.getActiveSessions().size(), is(1));
                assertThat(server.getActiveSessions().getFirst().isAuthenticated(), is(true));
            } finally {
                transport.getSshSessionFactory().releaseSession(session);
            }
        }
    }
}
