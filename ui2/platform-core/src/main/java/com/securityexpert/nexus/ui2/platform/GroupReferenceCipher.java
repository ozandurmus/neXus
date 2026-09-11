package com.securityexpert.nexus.ui2.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope encryption for {@code role_bindings.group_reference_encrypted}
 * (C3 §4.2, following C1 §6.3's envelope pattern: a wrapping key resolved
 * through the {@code <VAR>_FILE} mechanism, recorded as a
 * {@code secrets_metadata} row with {@code purpose =
 * 'role_binding_group_ref_key'}). The directory's own group reference is
 * never held in plaintext outside the in-flight value passed to
 * {@link #encrypt} and returned from {@link #decrypt}.
 *
 * <p>Wire format: a 12-byte random nonce, followed by the GCM ciphertext
 * (tag included) — nonce first so {@link #decrypt} can recover it without a
 * separate column.</p>
 */
public final class GroupReferenceCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;

    private GroupReferenceCipher(SecretKeySpec key) {
        this.key = key;
    }

    /** @param base64Key a base64-encoded 256-bit key, as read from a {@code _FILE} secret (never a default/literal). */
    public static GroupReferenceCipher fromBase64Key(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.strip());
        if (raw.length != 32) {
            throw new IllegalArgumentException("group reference encryption key must decode to 32 bytes (AES-256)");
        }
        return new GroupReferenceCipher(new SecretKeySpec(raw, "AES"));
    }

    public byte[] encrypt(String plaintextGroupReference) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintextGroupReference.getBytes(StandardCharsets.UTF_8));
            byte[] wire = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, wire, 0, nonce.length);
            System.arraycopy(ciphertext, 0, wire, nonce.length, ciphertext.length);
            return wire;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("group reference encryption failed", e);
        }
    }

    public String decrypt(byte[] wire) {
        if (wire.length < NONCE_LENGTH_BYTES) {
            throw new IllegalArgumentException("encrypted group reference is too short to contain a nonce");
        }
        byte[] nonce = Arrays.copyOfRange(wire, 0, NONCE_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(wire, NONCE_LENGTH_BYTES, wire.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("group reference decryption failed", e);
        }
    }
}
