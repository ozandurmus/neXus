package com.securityexpert.nexus.ui2.persistence.artefact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The only form in which a backup credential reference reaches {@code credential_failure_alert} or a notification:
 * the first 12 hex characters of the SHA-256 of the reference string. The reference itself is never stored or sent.
 */
public final class CredentialRefDigest {

    public static final int LENGTH = 12;

    private CredentialRefDigest() {
    }

    public static String of(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("credentialRef");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(credentialRef.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
