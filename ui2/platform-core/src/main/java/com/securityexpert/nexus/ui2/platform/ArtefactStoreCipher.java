package com.securityexpert.nexus.ui2.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption for the artefact store (C7 section 4.1, 14G CG-9):
 * every artefact gets a fresh random 256-bit AES data key
 * ({@link #generateDataKey()}), the bytes are encrypted under that key
 * ({@link #encryptingStream(OutputStream, SecretKeySpec)}), and the data key
 * itself is wrapped under this instance's master key
 * ({@link #wrapKey(SecretKeySpec)}) so the wrapped form -- never the raw data
 * key -- is what a caller persists. AES-256-GCM throughout (a 12-byte random
 * nonce, then GCM ciphertext with the tag appended), mirroring {@link
 * CredentialStoreCipher}'s wire format. Under its own, separately named key
 * (C1 section 6.1) -- this key is never the credential store's or the
 * role-binding key.
 *
 * <p><b>BK-15 invariant: the master key never encrypts artefact bytes
 * directly.</b> Every stream method here takes an explicit {@link
 * SecretKeySpec} data key; there is no overload that encrypts or decrypts
 * artefact bytes under {@link #key} itself. {@link #key} is used only by
 * {@link #wrapKey(SecretKeySpec)}/{@link #unwrapKey(byte[])}, on the data
 * key's 32 raw bytes -- never on an artefact-sized stream.</p>
 *
 * <p>Rotation (section 4.3): {@link #rewrap(byte[], ArtefactStoreCipher)}
 * unwraps a stored wrapped data key under this instance's master key and
 * re-wraps it under a different instance's (the new master key) -- the
 * artefact's ciphertext bytes are never touched, decrypted or re-encrypted.
 * Wiring an operator entry point that drives this across every stored
 * manifest row is a separate, later concern (named in this movement's
 * SESSION_CLOSE); this class provides only the pure re-wrap operation and
 * its own key-id label.</p>
 */
public final class ArtefactStoreCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int DATA_KEY_LENGTH_BYTES = 32;

    /** The key id a freshly bootstrapped master key carries when no explicit id is given. */
    public static final String KEY_ID = "v1";

    private final SecretKeySpec key;
    private final String keyId;

    private ArtefactStoreCipher(SecretKeySpec key, String keyId) {
        this.key = key;
        this.keyId = keyId;
    }

    /** @param base64Key a base64-encoded 256-bit key, as read from {@code UI2_ARTEFACT_STORE_KEY_FILE} (never a default/literal). */
    public static ArtefactStoreCipher fromBase64Key(String base64Key) {
        return fromBase64Key(base64Key, KEY_ID);
    }

    /**
     * @param base64Key a base64-encoded 256-bit key (never a default/literal)
     * @param keyId the label this master key generation is recorded under on every manifest row it wraps a data key for -- distinct ids let a later rotation identify which rows still carry the old generation
     */
    public static ArtefactStoreCipher fromBase64Key(String base64Key, String keyId) {
        byte[] raw = Base64.getDecoder().decode(base64Key.strip());
        if (raw.length != DATA_KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("artefact store encryption key must decode to 32 bytes (AES-256)");
        }
        return new ArtefactStoreCipher(new SecretKeySpec(raw, "AES"), Objects.requireNonNull(keyId, "keyId"));
    }

    /** The label this instance's master key is recorded under on a manifest row (BK-16 {@code key_id}). */
    public String keyId() {
        return keyId;
    }

    /** A fresh, random 256-bit AES data key -- never reused across artefacts (BK-15: "every artefact gets a fresh random data key"). */
    public SecretKeySpec generateDataKey() {
        byte[] raw = new byte[DATA_KEY_LENGTH_BYTES];
        secureRandom().nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * Wraps {@code dataKey} under this instance's master key: a fresh random
     * nonce followed by the GCM ciphertext (tag included) of the data key's
     * 32 raw bytes. The returned bytes are what a caller persists as {@code
     * wrapped_data_key} -- never the raw key.
     */
    public byte[] wrapKey(SecretKeySpec dataKey) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] wrapped = cipher.doFinal(dataKey.getEncoded());
            byte[] wire = new byte[nonce.length + wrapped.length];
            System.arraycopy(nonce, 0, wire, 0, nonce.length);
            System.arraycopy(wrapped, 0, wire, nonce.length, wrapped.length);
            return wire;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("artefact data key could not be wrapped", e);
        }
    }

    /** Reverses {@link #wrapKey(SecretKeySpec)} under this instance's master key. */
    public SecretKeySpec unwrapKey(byte[] wrappedDataKey) {
        if (wrappedDataKey.length < NONCE_LENGTH_BYTES) {
            throw new IllegalArgumentException("wrapped data key is too short to contain a nonce");
        }
        byte[] nonce = Arrays.copyOfRange(wrappedDataKey, 0, NONCE_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(wrappedDataKey, NONCE_LENGTH_BYTES, wrappedDataKey.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new SecretKeySpec(cipher.doFinal(ciphertext), "AES");
        } catch (GeneralSecurityException e) {
            // Never embeds ciphertext or key material in the message (mirrors CredentialStoreCipher).
            throw new IllegalStateException("artefact data key could not be unwrapped -- wrong master key or "
                    + "tampered wrapped key", e);
        }
    }

    /**
     * BK-15 section 4.3's rotation operation: unwraps {@code wrappedDataKey}
     * under this instance's master key and re-wraps it under {@code
     * newCipher}'s. The artefact's ciphertext bytes are never read, touched
     * or re-encrypted -- only the small wrapped-key value changes. The
     * caller persists the returned bytes as the manifest row's new {@code
     * wrapped_data_key} alongside {@code newCipher.keyId()} as its new
     * {@code key_id}.
     */
    public byte[] rewrap(byte[] wrappedDataKey, ArtefactStoreCipher newCipher) {
        SecretKeySpec dataKey = unwrapKey(wrappedDataKey);
        return newCipher.wrapKey(dataKey);
    }

    /**
     * Writes a fresh random nonce to {@code sink} and returns a stream that
     * encrypts every byte written to it under {@code dataKey} (never this
     * instance's master key -- BK-15), appending the GCM tag on {@code
     * close()}. The caller must close the returned stream (which does not
     * close {@code sink} itself is a JDK detail of {@link CipherOutputStream}:
     * it does close the underlying stream -- callers layer {@code sink}
     * from the innermost writer outward and close only the outermost one).
     */
    public OutputStream encryptingStream(OutputStream sink, SecretKeySpec dataKey) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom().nextBytes(nonce);
            sink.write(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new CipherOutputStream(sink, cipher);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("artefact store encryption stream could not be initialized", e);
        }
    }

    /** Reads the nonce prefix from {@code source} and returns a stream that decrypts the remaining bytes under {@code dataKey}. */
    public InputStream decryptingStream(InputStream source, SecretKeySpec dataKey) throws IOException {
        byte[] nonce = source.readNBytes(NONCE_LENGTH_BYTES);
        if (nonce.length != NONCE_LENGTH_BYTES) {
            throw new IllegalArgumentException("encrypted artefact is too short to contain a nonce");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new CipherInputStream(source, cipher);
        } catch (GeneralSecurityException e) {
            // Never embeds ciphertext or key material in the message (mirrors CredentialStoreCipher).
            throw new IllegalStateException("artefact store decryption failed", e);
        }
    }

    private static SecureRandom secureRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
