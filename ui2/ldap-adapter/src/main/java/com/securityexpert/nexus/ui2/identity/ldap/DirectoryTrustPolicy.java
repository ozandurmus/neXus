package com.securityexpert.nexus.ui2.identity.ldap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import com.unboundid.ldap.sdk.LDAPConnection;

/** One validated in-memory generation shared by operator and service binds. */
public final class DirectoryTrustPolicy {
    public record Snapshot(long generation, SSLSocketFactory socketFactory) { }
    private final Object reloadLock = new Object();
    private final DirectoryProfile identity;
    private Snapshot current;
    private long generation;
    private final Set<LDAPConnection> connections = new HashSet<>();
    private final Runnable invalidateDirectoryFreshness;

    public DirectoryTrustPolicy(DirectoryProfile profile, Runnable invalidateDirectoryFreshness) {
        this.identity = profile;
        this.invalidateDirectoryFreshness = java.util.Objects.requireNonNull(invalidateDirectoryFreshness);
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

    boolean compatible(DirectoryProfile profile) {
        return identity.profileId().equals(profile.profileId()) && identity.host().equals(profile.host())
                && identity.port() == profile.port() && identity.transport() == profile.transport()
                && identity.bindDnTemplate().equals(profile.bindDnTemplate())
                && identity.groupSearchBaseDn().equals(profile.groupSearchBaseDn())
                && identity.accessGroupReference().equals(profile.accessGroupReference());
    }

    public void reload(DirectoryProfile profile) {
        synchronized (reloadLock) {
            SSLSocketFactory factory;
            try {
                if (!compatible(profile)) throw new IllegalArgumentException();
                factory = load(profile);
            } catch (RuntimeException e) {
                retire();
                throw new LdapStartupException("directory_trust_load_refused");
            }
            retire();
            synchronized (this) { current = new Snapshot(++generation, factory); }
        }
    }

    public void withdraw() {
        synchronized (reloadLock) { retire(); }
    }

    private void retire() {
        synchronized (this) {
            current = null;
            for (LDAPConnection connection : connections) connection.close();
            connections.clear();
        }
        // Do not hold the trust monitor while waiting for a database mutation's locks.
        invalidateDirectoryFreshness.run();
    }

    static SSLSocketFactory load(DirectoryProfile profile) { return load(profile, DirectoryTrustPolicy::readSecret); }

    static SSLSocketFactory load(DirectoryProfile profile, java.util.function.Function<Path, char[]> readPin) {
        byte[] material = null;
        char[] pin = null;
        try {
            material = Files.readAllBytes(profile.trustMaterialFile());
            if (material.length == 0 || material.length > 4 * 1024 * 1024) throw new IllegalArgumentException();
            KeyStore store;
            if (profile.format() == DirectoryProfile.Format.PEM) {
                store = KeyStore.getInstance("JKS");
                store.load(null, null);
                // Reject keys, comments and trailing content, rather than let the permissive X.509 parser skip it.
                String text = new String(material, StandardCharsets.US_ASCII);
                var matcher = java.util.regex.Pattern.compile(
                        "-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----").matcher(text);
                int end = 0;
                int count = 0;
                while (matcher.find()) {
                    if (!text.substring(end, matcher.start()).isBlank()) throw new IllegalArgumentException();
                    byte[] der = java.util.Base64.getMimeDecoder().decode(matcher.group()
                            .replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", ""));
                    try {
                        ByteArrayInputStream encoded = new ByteArrayInputStream(der);
                        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                                .generateCertificate(encoded);
                        if (encoded.available() != 0) throw new IllegalArgumentException();
                        store.setCertificateEntry("anchor-" + count++, certificate);
                    } finally { Arrays.fill(der, (byte) 0); }
                    end = matcher.end();
                }
                if (count == 0 || !text.substring(end).isBlank()) throw new IllegalArgumentException();
            } else {
                boolean jksHeader = material.length >= 4 && material[0] == (byte) 0xfe && material[1] == (byte) 0xed
                        && material[2] == (byte) 0xfe && material[3] == (byte) 0xed;
                if ((profile.format() == DirectoryProfile.Format.JKS) != jksHeader) throw new IllegalArgumentException();
                pin = readPin.apply(profile.storePinFile());
                if (pin == null || pin.length == 0) throw new IllegalArgumentException();
                store = KeyStore.getInstance(profile.format().name());
                ByteArrayInputStream encoded = new ByteArrayInputStream(material);
                store.load(encoded, pin);
                if (encoded.available() != 0) throw new IllegalArgumentException();
            }
            int anchors = 0;
            var aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!store.isCertificateEntry(alias) || !(store.getCertificate(alias) instanceof X509Certificate ca)
                        || ca.getBasicConstraints() < 0) throw new IllegalArgumentException();
                ca.checkValidity();
                boolean[] usage = ca.getKeyUsage();
                if (usage != null && (usage.length <= 5 || !usage[5])) throw new IllegalArgumentException();
                anchors++;
            }
            if (anchors == 0) throw new IllegalArgumentException();
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return new EndpointCheckingSocketFactory(context.getSocketFactory());
        } catch (Exception e) {
            throw new LdapStartupException("directory_trust_load_refused");
        } finally {
            if (pin != null) Arrays.fill(pin, '\0');
            if (material != null) Arrays.fill(material, (byte) 0);
        }
    }

    /** JDK endpoint checking is mandatory: pinned SDK verifier exempts numeric loopback addresses. */
    private static final class EndpointCheckingSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        EndpointCheckingSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private java.net.Socket checked(java.net.Socket socket) {
            javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) socket;
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("LDAPS");
            tls.setSSLParameters(parameters);
            return tls;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket() throws java.io.IOException { return checked(delegate.createSocket()); }
        @Override public java.net.Socket createSocket(java.net.Socket socket, String host, int port, boolean close) throws java.io.IOException {
            return checked(delegate.createSocket(socket, host, port, close));
        }
        @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return checked(delegate.createSocket(host, port));
        }
        @Override public java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return checked(delegate.createSocket(host, port, local, localPort));
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return checked(delegate.createSocket(host, port));
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return checked(delegate.createSocket(host, port, local, localPort));
        }
    }

    static char[] readSecret(Path path) {
        byte[] bytes = null;
        CharBuffer decoded = null;
        try {
            bytes = Files.readAllBytes(path);
            if (bytes.length > 65536) throw new IllegalArgumentException();
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            int length = decoded.remaining();
            if (length > 0 && decoded.get(length - 1) == '\n') length--;
            if (length > 0 && decoded.get(length - 1) == '\r') length--;
            if (length == 0) throw new IllegalArgumentException();
            char[] result = new char[length];
            decoded.get(result);
            return result;
        } catch (Exception e) {
            throw new LdapStartupException("directory_secret_unavailable");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (decoded != null && decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
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
