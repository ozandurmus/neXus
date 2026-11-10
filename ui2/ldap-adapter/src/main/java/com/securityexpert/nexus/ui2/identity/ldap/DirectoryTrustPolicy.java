package com.securityexpert.nexus.ui2.identity.ldap;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import com.unboundid.ldap.sdk.LDAPConnection;

/**
 * One validated in-memory trust generation shared by sign-in, revalidation and the connection test (design §3).
 * A reload or withdrawal retires the generation: its open connections are closed and every directory actor's
 * group set is expired, so nothing proven under the old settings is published afterwards.
 */
public final class DirectoryTrustPolicy {
    public record Snapshot(long generation, SocketFactory socketFactory) { }

    private final Object reloadLock = new Object();
    private DirectoryProfile identity;
    private Snapshot current;
    private long generation;
    private final Set<LDAPConnection> connections = new HashSet<>();
    private final Runnable invalidateDirectoryFreshness;

    public DirectoryTrustPolicy(DirectoryProfile profile, Runnable invalidateDirectoryFreshness) {
        this.invalidateDirectoryFreshness = Objects.requireNonNull(invalidateDirectoryFreshness);
        reload(profile);
    }

    public synchronized Snapshot snapshot() {
        if (current == null) throw new LdapStartupException("directory_trust_unavailable");
        return current;
    }

    public synchronized boolean publish(Snapshot snapshot, Runnable write) {
        if (current != snapshot) return false;
        write.run();
        return true;
    }

    synchronized void register(Snapshot snapshot, LDAPConnection connection) {
        if (current != snapshot) {
            connection.close();
            throw new LdapStartupException("directory_trust_retired");
        }
        connections.add(connection);
    }

    synchronized void release(LDAPConnection connection) {
        connections.remove(connection);
        connection.close();
    }

    synchronized boolean compatible(DirectoryProfile profile) {
        return identity != null && identity.equals(profile);
    }

    public void reload(DirectoryProfile profile) {
        synchronized (reloadLock) {
            SocketFactory factory;
            try {
                factory = socketFactory(Objects.requireNonNull(profile));
            } catch (RuntimeException e) {
                retire();
                throw new LdapStartupException("directory_trust_load_refused");
            }
            retire();
            synchronized (this) {
                identity = profile;
                current = new Snapshot(++generation, factory);
            }
        }
    }

    public void withdraw() {
        synchronized (reloadLock) { retire(); }
    }

    private void retire() {
        boolean published;
        synchronized (this) {
            // Only a generation this policy actually served can have proven anything; the first load of a fresh
            // policy (a pod start) must not expire every directory session.
            published = generation > 0;
            current = null;
            for (LDAPConnection connection : connections) connection.close();
            connections.clear();
        }
        // Do not hold the trust monitor while waiting for a database mutation's locks.
        if (published) invalidateDirectoryFreshness.run();
    }

    static SocketFactory socketFactory(DirectoryProfile profile) {
        if (!profile.ldaps()) return SocketFactory.getDefault();
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (profile.trustMode() == DirectoryProfile.TrustMode.TRUST_ANY) {
                // PO decision 2026-11-11 (PO_DECISION_RECORD_2026_11_11_LDAP_SERVICE_BIND.md item 3): no chain and
                // no host-name check. An X509ExtendedTrustManager, so the JDK adds no check of its own.
                context.init(null, new javax.net.ssl.TrustManager[] { new AcceptAnyTrustManager() }, null);
                return context.getSocketFactory();
            }
            List<X509Certificate> anchors = DirectoryProfile.parsePem(profile.trustPem());
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            for (int i = 0; i < anchors.size(); i++) store.setCertificateEntry("anchor-" + i, anchors.get(i));
            TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX");
            managers.init(store);
            X509ExtendedTrustManager pkix = null;
            for (var manager : managers.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager extended) pkix = extended;
            }
            if (pkix == null) throw new IllegalStateException("no PKIX trust manager");
            context.init(null, new javax.net.ssl.TrustManager[] { new ValidityCheckingTrustManager(pkix) }, null);
            return new EndpointCheckingSocketFactory(context.getSocketFactory());
        } catch (Exception e) {
            throw new LdapStartupException("directory_trust_load_refused");
        }
    }

    /**
     * PKIX accepts a presented certificate that is itself a pasted anchor without checking its dates (Argon review
     * finding 2), so a pinned server certificate would stay trusted after it expires. Every presented certificate
     * must be inside its validity window at handshake time, after PKIX accepted the chain.
     */
    private static final class ValidityCheckingTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        ValidityCheckingTrustManager(X509ExtendedTrustManager delegate) { this.delegate = delegate; }
        private static void dates(X509Certificate[] chain) throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("empty chain");
            for (X509Certificate certificate : chain) certificate.checkValidity();
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("client certificates are not accepted");
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("client certificates are not accepted");
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("client certificates are not accepted");
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            delegate.checkServerTrusted(chain, authType);
            dates(chain);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws java.security.cert.CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
            dates(chain);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws java.security.cert.CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
            dates(chain);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
    }

    /** Trust any certificate: the PO-owned mode. Checks nothing, by decision. */
    private static final class AcceptAnyTrustManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /**
     * Certificate-below mode: JDK endpoint identification "LDAPS" on every socket. A host name must match a DNS
     * SAN (or the CN when no SAN exists); an IP address must match an IP SAN.
     */
    private static final class EndpointCheckingSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        EndpointCheckingSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket checked(Socket socket) {
            SSLSocket tls = (SSLSocket) socket;
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("LDAPS");
            tls.setSSLParameters(parameters);
            return tls;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws java.io.IOException { return checked(delegate.createSocket()); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws java.io.IOException {
            return checked(delegate.createSocket(socket, host, port, close));
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return checked(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return checked(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return checked(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return checked(delegate.createSocket(host, port, local, localPort));
        }
    }

    static byte[] passwordBytes(char[] password) {
        ByteBuffer buffer = null;
        try {
            buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        } catch (Exception e) {
            throw new LdapStartupException("directory_credential_invalid");
        } finally {
            if (buffer != null && buffer.hasArray()) Arrays.fill(buffer.array(), (byte) 0);
        }
    }
}
