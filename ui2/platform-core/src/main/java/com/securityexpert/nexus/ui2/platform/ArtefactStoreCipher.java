package com.securityexpert.nexus.ui2.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope encryption for the configuration-collection artefact
 * store (14G CG-9, C7 section 4.1 minimum: "raw configuration exists only
 * encrypted in the artefact store"). Mirrors {@link CredentialStoreCipher}'s
 * wire format (a 12-byte random nonce, then GCM ciphertext with the tag
 * appended) but exposes stream-based encrypt/decrypt rather than
 * whole-array, since an artefact (Palo Alto {@code effective-running},
 * 11.8 MB measured) is never held whole in memory (14G CG-5, this
 * movement's own invariant). Under its own, separately named key -- C1
 * section 6.1 scopes secrets per purpose, so this key is never the
 * credential store's or the role-binding key.
 *
 * <p>This is the minimum viable form C7 section 4 describes for "store an
 * artefact": one master key encrypts directly (no per-artefact wrapped
 * data key). C7's full envelope model (master key wraps a fresh
 * per-artefact DEK, rotation re-wraps DEKs without touching artefact
 * bytes) is not implemented here -- {@code key_id} is recorded on every
 * stored artefact for forward compatibility, but rotation is out of this
 * movement's scope.</p>
 */
public final class ArtefactStoreCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    /** The only key id this movement issues; recorded on every artefact row so a later rotation has a value to key on. */
    public static final String KEY_ID = "v1";

    private final SecretKeySpec key;

    private ArtefactStoreCipher(SecretKeySpec key) {
        this.key = key;
    }

    /** @param base64Key a base64-encoded 256-bit key, as read from {@code UI2_ARTEFACT_STORE_KEY_FILE} (never a default/literal). */
    public static ArtefactStoreCipher fromBase64Key(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.strip());
        if (raw.length != 32) {
            throw new IllegalArgumentException("artefact store encryption key must decode to 32 bytes (AES-256)");
        }
        return new ArtefactStoreCipher(new SecretKeySpec(raw, "AES"));
    }

    /**
     * Writes a fresh random nonce to {@code sink} and returns a stream that
     * encrypts every byte written to it, appending the GCM tag on {@code
     * close()}. The caller must close the returned stream (which does not
     * close {@code sink} itself is a JDK detail of {@link CipherOutputStream}:
     * it does close the underlying stream -- callers layer {@code sink}
     * from the innermost writer outward and close only the outermost one).
     */
    public OutputStream encryptingStream(OutputStream sink) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(nonce);
            sink.write(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new CipherOutputStream(sink, cipher);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("artefact store encryption stream could not be initialized", e);
        }
    }

    /** Reads the nonce prefix from {@code source} and returns a stream that decrypts the remaining bytes. */
    public InputStream decryptingStream(InputStream source) throws IOException {
        byte[] nonce = source.readNBytes(NONCE_LENGTH_BYTES);
        if (nonce.length != NONCE_LENGTH_BYTES) {
            throw new IllegalArgumentException("encrypted artefact is too short to contain a nonce");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new CipherInputStream(source, cipher);
        } catch (GeneralSecurityException e) {
            // Never embeds ciphertext or key material in the message (mirrors CredentialStoreCipher).
            throw new IllegalStateException("artefact store decryption failed", e);
        }
    }
}
