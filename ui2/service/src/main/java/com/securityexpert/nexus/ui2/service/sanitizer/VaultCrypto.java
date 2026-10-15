package com.securityexpert.nexus.ui2.service.sanitizer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;

/**
 * The mapping vault's cryptography (DATA_SANITIZER_CONTRACT.md §2 "encrypted at rest with the artefact-store envelope
 * key"): every sanitize job gets a fresh AES-256 data key, wrapped by the artefact-store master key exactly as an
 * artefact's is ({@link ArtefactStoreCipher#wrapKey}); each original is sealed under it with AES-256-GCM, a random
 * 96-bit nonce and the job id and lookup key as associated data (a sealed value cannot be moved to another row).
 * The token itself is never stored: its row is found by {@code HMAC-SHA256(vault sub-key of the privacy key, kind
 * || 0x00 || canonical token)}.
 */
public final class VaultCrypto {

    /** A job's data key with its wrapped form and the master key's id, as the job row stores them. */
    public record JobKey(SecretKeySpec dataKey, byte[] wrapped, String keyId) {
        @Override
        public String toString() {
            return "JobKey[redacted]";
        }
    }

    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final ArtefactStoreCipher envelope;
    private final ThreadLocal<Mac> lookup;
    private final SecureRandom random = new SecureRandom();

    public VaultCrypto(byte[] privacyKey, ArtefactStoreCipher envelope) {
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        byte[] vaultKey = SanitizerKey.of(privacyKey).subKey("vault");
        this.lookup = ThreadLocal.withInitial(() -> SanitizerKey.mac(vaultKey));
    }

    public byte[] lookupKey(VaultKey key) {
        Mac mac = lookup.get();
        mac.update(key.kind().id().getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        return mac.doFinal(key.canonical().getBytes(StandardCharsets.UTF_8));
    }

    public JobKey newJobKey() {
        SecretKeySpec dataKey = envelope.generateDataKey();
        return new JobKey(dataKey, envelope.wrapKey(dataKey), envelope.keyId());
    }

    public SecretKeySpec unwrap(byte[] wrapped) {
        return envelope.unwrapKey(wrapped);
    }

    public byte[] seal(SecretKeySpec dataKey, String jobId, byte[] lookupKey, String original) {
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(jobId, lookupKey));
            byte[] sealed = cipher.doFinal(original.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("vault entry could not be sealed", e);
        }
    }

    public String unseal(SecretKeySpec dataKey, String jobId, byte[] lookupKey, byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, Arrays.copyOf(sealed, NONCE)));
            cipher.updateAAD(aad(jobId, lookupKey));
            return new String(cipher.doFinal(sealed, NONCE, sealed.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Never echoes key or ciphertext material.
            throw new IllegalStateException("vault entry could not be unsealed -- wrong key or tampered row", e);
        }
    }

    private static byte[] aad(String jobId, byte[] lookupKey) {
        byte[] id = jobId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(id.length + 1 + lookupKey.length).put(id).put((byte) 0).put(lookupKey).array();
    }
}
