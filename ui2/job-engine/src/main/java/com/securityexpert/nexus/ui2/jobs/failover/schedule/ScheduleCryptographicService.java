package com.securityexpert.nexus.ui2.jobs.failover.schedule;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cryptographic service providing purpose-separated key derivation and constant-time HMAC-SHA256
 * signing and verification for FailoverScheduleEnvelope records.
 */
public class ScheduleCryptographicService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HKDF_INFO = "NEXUS_FAILOVER_SCHEDULE_V1";

    private final byte[] scheduleSigningKey;

    public ScheduleCryptographicService(byte[] masterSecretKey) {
        Objects.requireNonNull(masterSecretKey, "masterSecretKey must not be null");
        if (masterSecretKey.length < 32) {
            throw new IllegalArgumentException("Master secret key must be at least 32 bytes for HMAC-SHA256 security");
        }
        // Derive schedule-specific subkey using HKDF-Expand-like HMAC construction
        this.scheduleSigningKey = deriveScheduleSubkey(masterSecretKey, HKDF_INFO);
    }

    public String signEnvelope(FailoverScheduleEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        byte[] payload = envelope.toCanonicalBytes();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(scheduleSigningKey, HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(payload);
            return HexFormat.of().formatHex(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC signature: " + e.getMessage(), e);
        }
    }

    public boolean verifyEnvelope(FailoverScheduleEnvelope envelope, String expectedHexSignature) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (expectedHexSignature == null || expectedHexSignature.isBlank()) {
            return false;
        }

        byte[] expectedBytes;
        try {
            expectedBytes = HexFormat.of().parseHex(expectedHexSignature);
        } catch (IllegalArgumentException malformedHex) {
            // Malformed hex (odd length, non-hex characters) can never be a valid signature.
            return false;
        }

        byte[] computedBytes = rawSignatureBytes(envelope);

        // Constant-time comparison over raw signature bytes to prevent timing side-channel leakage.
        return MessageDigest.isEqual(expectedBytes, computedBytes);
    }

    private byte[] rawSignatureBytes(FailoverScheduleEnvelope envelope) {
        byte[] payload = envelope.toCanonicalBytes();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(scheduleSigningKey, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC signature: " + e.getMessage(), e);
        }
    }

    private static byte[] deriveScheduleSubkey(byte[] masterKey, String info) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(masterKey, HMAC_ALGORITHM));
            mac.update(info.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0x01); // 1 iteration (HKDF-Expand step)
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Subkey derivation failed: " + e.getMessage(), e);
        }
    }
}
