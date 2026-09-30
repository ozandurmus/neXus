package com.securityexpert.nexus.ui2.worker.transport.https;

import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Certificate;

/** Trust is the persisted leaf pin, not a public-root chain; the callback runs before HTTP credentials are sent. */
final class CertificatePinningTrustManager extends X509ExtendedTrustManager {
    @FunctionalInterface interface Observer { void observe(Certificate certificate) throws CertificateException; }
    private final Observer observer;

    CertificatePinningTrustManager(Observer observer) { this.observer = observer; }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("missing HTTPS leaf certificate");
        try {
            X509Certificate leaf = chain[0];
            observer.observe(new Certificate(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(leaf.getEncoded())), cn(leaf.getSubjectX500Principal()), cn(leaf.getIssuerX500Principal()),
                    leaf.getNotAfter().toInstant()));
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            // Database and parsing failures must refuse without exposing certificate/endpoint material.
            throw new CertificateException("HTTPS certificate trust unavailable");
        }
    }

    private static String cn(X500Principal principal) throws javax.naming.InvalidNameException {
        for (Rdn rdn : new LdapName(principal.getName(X500Principal.RFC2253)).getRdns())
            if ("CN".equalsIgnoreCase(rdn.getType())) return String.valueOf(rdn.getValue());
        return "";
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkServerTrusted(chain, authType);
    }
    @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkServerTrusted(chain, authType);
    }
    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("client certificates unsupported");
    }
    @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkClientTrusted(chain, authType);
    }
    @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkClientTrusted(chain, authType);
    }
    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
}
