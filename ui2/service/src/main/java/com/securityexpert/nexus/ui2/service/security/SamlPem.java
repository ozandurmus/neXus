package com.securityexpert.nexus.ui2.service.security;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PEM parsing for the SAML settings: X.509 certificates and an unencrypted PKCS#8 private key. Pure JDK. */
final class SamlPem {
    private static final Pattern CERT = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----");
    private static final Pattern KEY = Pattern.compile(
            "-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+?)-----END PRIVATE KEY-----");

    private SamlPem() { }

    /** Every certificate in the text, in order; empty when none parses. */
    static List<X509Certificate> certificates(String pem) {
        List<X509Certificate> result = new ArrayList<>();
        if (pem == null) return result;
        Matcher m = CERT.matcher(pem);
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            while (m.find()) {
                byte[] der = Base64.getMimeDecoder().decode(m.group(1));
                result.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return new ArrayList<>();
        }
        return result;
    }

    /** True when the text holds one or more certificates and nothing else but whitespace. */
    static boolean onlyCertificates(String pem) {
        return !certificates(pem).isEmpty() && CERT.matcher(pem).replaceAll("").isBlank();
    }

    static String toPem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An unencrypted PKCS#8 RSA or EC private key; null when it does not parse. */
    static PrivateKey privateKey(String pem) {
        if (pem == null) return null;
        Matcher m = KEY.matcher(pem);
        if (!m.find() || !KEY.matcher(pem).replaceFirst("").isBlank()) return null;
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (String algorithm : new String[] { "RSA", "EC" }) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // try the next algorithm
            }
        }
        return null;
    }

    /** True when {@code key} signs what {@code certificate}'s public key verifies. */
    static boolean matches(PrivateKey key, X509Certificate certificate) {
        try {
            PublicKey publicKey = certificate.getPublicKey();
            String algorithm = "RSA".equals(key.getAlgorithm()) ? "SHA256withRSA" : "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : null;
            if (algorithm == null) return false;
            byte[] probe = "nexus-saml-sp-key-check".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static boolean anyValidAt(List<X509Certificate> certificates, Instant at) {
        for (X509Certificate certificate : certificates) {
            if (!at.isBefore(certificate.getNotBefore().toInstant()) && !at.isAfter(certificate.getNotAfter().toInstant())) return true;
        }
        return false;
    }
}
