package io.kestra.plugin.git.shared.services;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.security.GeneralSecurityException;

import javax.net.ssl.TrustManager;

import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.apache.HttpClientConnection;

/**
 * Wraps the connection factory in effect and applies a task's trust managers to each HTTPS connection it creates,
 * so the CA is trusted by that transport only.
 *
 * <p>Deliberately a plain {@link HttpConnectionFactory}, not {@code HttpConnectionFactory2}: with the latter,
 * {@code TransportHttp} re-applies a session that rebuilds the socket factory from {@code SSLContext.getDefault()}
 * and discards the trust set here.
 */
public class TrustedCaHttpConnectionFactory implements HttpConnectionFactory {
    private final HttpConnectionFactory delegate;
    private final TrustManager[] trustManagers;

    public TrustedCaHttpConnectionFactory(HttpConnectionFactory delegate, TrustManager[] trustManagers) {
        this.delegate = delegate;
        this.trustManagers = trustManagers.clone();
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return secure(url, delegate.create(url));
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        return secure(url, delegate.create(url, proxy));
    }

    private HttpConnection secure(URL url, HttpConnection connection) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            return connection;
        }
        try {
            connection.configure(null, trustManagers, null);
            if (connection instanceof HttpClientConnection) {
                // Without a verifier the Apache connection ignores the configured context and uses the JVM default;
                // this is the strict verifier it would use anyway.
                connection.setHostnameVerifier(SSLConnectionSocketFactory.getDefaultHostnameVerifier());
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot apply `trustedCaPemPath` to the HTTPS connection: " + e.getMessage(), e);
        }
        return connection;
    }
}
