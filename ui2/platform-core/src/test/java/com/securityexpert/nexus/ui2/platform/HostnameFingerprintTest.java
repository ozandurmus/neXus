package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** BK-16: hostname_fingerprint is an HMAC, never the raw hostname. */
class HostnameFingerprintTest {

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void fingerprintNeverEqualsTheInputHostname() {
        HostnameFingerprint fingerprint = HostnameFingerprint.fromBase64Key(randomKey());
        String hostname = "gw-edge-01.example.internal";

        String fingerprinted = fingerprint.of(hostname);

        assertNotEquals(hostname, fingerprinted, "hostname_fingerprint must never equal the raw hostname (BK-16)");
        assertTrue(fingerprinted.chars().allMatch(c -> Character.digit(c, 16) >= 0),
                "fingerprint must be lowercase hex only: " + fingerprinted);
        assertEquals(64, fingerprinted.length(), "HMAC-SHA256 hex is 64 characters");
    }

    @Test
    void deterministicUnderTheSameKey() {
        String key = randomKey();
        HostnameFingerprint fingerprint = HostnameFingerprint.fromBase64Key(key);
        String hostname = "gw-edge-01.example.internal";

        assertEquals(fingerprint.of(hostname), fingerprint.of(hostname));
    }

    @Test
    void differentKeysYieldDifferentFingerprintsForTheSameHostname() {
        String hostname = "gw-edge-01.example.internal";
        HostnameFingerprint a = HostnameFingerprint.fromBase64Key(randomKey());
        HostnameFingerprint b = HostnameFingerprint.fromBase64Key(randomKey());

        assertNotEquals(a.of(hostname), b.of(hostname));
    }

    @Test
    void differentHostnamesYieldDifferentFingerprintsUnderTheSameKey() {
        HostnameFingerprint fingerprint = HostnameFingerprint.fromBase64Key(randomKey());

        assertNotEquals(fingerprint.of("gw-edge-01.example.internal"), fingerprint.of("gw-edge-02.example.internal"));
    }

    @Test
    void contentDigestsAreKeyedDomainSeparatedAndIncremental() throws Exception {
        HostnameFingerprint a = HostnameFingerprint.fromBase64Key(randomKey());
        HostnameFingerprint b = HostnameFingerprint.fromBase64Key(randomKey());
        byte[] data = "synthetic log line\n\u00ff".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String oneShot = a.ofContent("chunk", data, 0, data.length);
        var incremental = a.content("chunk");
        incremental.update(data, 0, 5);
        incremental.update(data, 5, data.length - 5);
        assertEquals(oneShot, incremental.finish());
        assertTrue(oneShot.matches("[0-9a-f]{64}"));
        assertNotEquals(oneShot, a.ofContent("anchor", data, 0, data.length), "each use has its own domain");
        assertNotEquals(oneShot, b.ofContent("chunk", data, 0, data.length), "keyed: another key, another digest");
        String bare = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        assertNotEquals(bare, oneShot, "never the bare hash");
    }
}
