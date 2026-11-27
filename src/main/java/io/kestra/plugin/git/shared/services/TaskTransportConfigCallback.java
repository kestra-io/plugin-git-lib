package io.kestra.plugin.git.shared.services;

import javax.net.ssl.TrustManager;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;

/**
 * Applies SSH key setup to SSH transports and the task's trusted CA to HTTP(S) transports, so the two settings
 * compose instead of one replacing the other.
 */
public class TaskTransportConfigCallback implements TransportConfigCallback {
    private final SshTransportConfigCallback ssh;
    private final TrustManager[] trustManagers;

    /**
     * @param ssh           applied to {@link SshTransport}; may be null
     * @param trustManagers applied to {@link TransportHttp}; may be null
     */
    public TaskTransportConfigCallback(SshTransportConfigCallback ssh, TrustManager[] trustManagers) {
        this.ssh = ssh;
        this.trustManagers = trustManagers == null ? null : trustManagers.clone();
    }

    @Override
    public void configure(Transport transport) {
        if (transport instanceof SshTransport && ssh != null) {
            ssh.configure(transport);
        } else if (transport instanceof TransportHttp http && trustManagers != null) {
            // Wrap the transport's own factory (captured at Transport.open), not the JVM-global one, which another
            // task on the same worker may have reconfigured since.
            http.setHttpConnectionFactory(
                new TrustedCaHttpConnectionFactory(http.getHttpConnectionFactory(), trustManagers)
            );
        }
    }
}
