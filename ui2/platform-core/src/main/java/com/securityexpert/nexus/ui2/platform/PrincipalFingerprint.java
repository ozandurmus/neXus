package com.securityexpert.nexus.ui2.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Java port of {@code utils/logger.py::principal_fingerprint} (C3 §3.2):
 * a 12-hex-character, <b>unkeyed</b> SHA-256 prefix of the bind DN. This is
 * the existing, established correlator — {@code sessions.actor_fingerprint},
 * {@code role_bindings.created_by_actor_fingerprint},
 * {@code jobs.submitted_by_actor_fingerprint} and {@code audit_log.actor_fingerprint}
 * all speak this identical value for the same directory identity.
 *
 * <p>This class adopts the algorithm; it does not change it. {@code SR-D10}'s
 * finding that an unkeyed fingerprint is reversible from a DN list is a
 * still-open Product Owner item (C3 §9 item 5), unaffected by this class.</p>
 */
public final class PrincipalFingerprint {

    private static final int PREFIX_LENGTH = 12;

    private PrincipalFingerprint() {
    }

    /**
     * @param principal the bind DN (or other directory principal identifier);
     *                   a {@code null} or blank value returns the same
     *                   {@code "anonymous"} sentinel the Python reference
     *                   implementation returns for a falsy principal.
     */
    public static String of(String principal) {
        if (principal == null || principal.isEmpty()) {
            return "anonymous";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(principal.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, PREFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS platform guarantee);
            // this branch is unreachable on any conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
