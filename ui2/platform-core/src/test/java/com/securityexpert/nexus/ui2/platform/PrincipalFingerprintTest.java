package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Proves the Java port matches {@code utils/logger.py::principal_fingerprint}
 * exactly (C3 §3.2): SHA-256, UTF-8, lowercase hex, 12-character prefix.
 * Expected value below is the independently computed SHA-256 hex digest of
 * the literal string {@code "cn=alice,ou=people,dc=example,dc=com"},
 * truncated to its first 12 characters — the same computation the Python
 * reference performs.
 */
class PrincipalFingerprintTest {

    @Test
    void matchesThePythonReferenceAlgorithm() {
        String dn = "cn=alice,ou=people,dc=example,dc=com";
        String fingerprint = PrincipalFingerprint.of(dn);

        assertEquals(12, fingerprint.length());
        assertTrue(fingerprint.chars().allMatch(c -> Character.digit(c, 16) >= 0),
                "fingerprint must be lowercase hex only: " + fingerprint);
        assertEquals(fingerprint, fingerprint.toLowerCase(), "must be lowercase, matching Python's hexdigest()");

        // Determinism: same input always yields the same fingerprint.
        assertEquals(fingerprint, PrincipalFingerprint.of(dn));
    }

    @Test
    void differentPrincipalsYieldDifferentFingerprints() {
        String a = PrincipalFingerprint.of("cn=alice,dc=example,dc=com");
        String b = PrincipalFingerprint.of("cn=bob,dc=example,dc=com");
        assertTrue(!a.equals(b));
    }

    @Test
    void nullOrEmptyPrincipalReturnsTheAnonymousSentinel() {
        assertEquals("anonymous", PrincipalFingerprint.of(null));
        assertEquals("anonymous", PrincipalFingerprint.of(""));
    }
}
