package io.kestra.plugin.git.shared.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.util.FS;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class SshTransportConfigCallback implements TransportConfigCallback {
    private byte[] privateKey;
    private String passphrase;
    /**
     * When {@code true}, the SSH session verifies the remote server's host key, either against
     * {@link #knownHosts} (if provided) or the system/user known_hosts file. Disabling this opens the connection
     * to man-in-the-middle attacks (CWE-297) and must be an explicit opt-in by the user.
     */
    private boolean strictHostKeyChecking;
    /**
     * Optional known_hosts file content (OpenSSH format) used to verify the remote server's
     * host key instead of relying on the system/user known_hosts file.
     */
    private String knownHosts;

    @Override
    public void configure(Transport transport) {
        SshTransport sshTransport = (SshTransport) transport;
        sshTransport.setSshSessionFactory(new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host hc, Session session) {
                session.setConfig("StrictHostKeyChecking", strictHostKeyChecking ? "yes" : "no");
            }

            @Override
            protected JSch getJSch(final OpenSshConfig.Host hc, FS fs) throws JSchException {
                JSch jsch = super.getJSch(hc, fs);
                jsch.removeAllIdentity();

                if (knownHosts != null && !knownHosts.isBlank()) {
                    jsch.setKnownHosts(new ByteArrayInputStream(knownHosts.getBytes(StandardCharsets.UTF_8)));
                }

                if (passphrase != null) {
                    jsch.addIdentity(
                        "privateKey",
                        privateKey,
                        null,
                        passphrase.getBytes(StandardCharsets.UTF_8)
                    );
                } else {
                    jsch.addIdentity("privateKey", privateKey, null, null);
                }

                return jsch;
            }
        });
    }
}
