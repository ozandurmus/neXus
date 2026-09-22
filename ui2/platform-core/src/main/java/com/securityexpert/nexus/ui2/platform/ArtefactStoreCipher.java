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
    /*
     * Bulk format v2 -- framed AES-GCM, streamable both ways.
     *
     * Measured live 2026-09-22: the v1 layout (one nonce, one GCM stream over the whole artefact)
     * cannot be decrypted in bounded memory -- the JDK's GCM decrypt buffers every ciphertext byte
     * until the tag verifies, so listing a 745 MB Gaia archive ran the worker out of heap, and a
     * 1.4 GB one could never be downloaded at all. v2 encrypts the plaintext in frames of at most
     * FRAME_PLAINTEXT_BYTES, each under its own nonce (8 random bytes + 4-byte frame counter) with
     * the frame index and a final flag as associated data, so a reordered, dropped or truncated
     * frame fails closed and each frame needs only its own size in memory.
     *
     * Wire: MAGIC(4) | nonce prefix(8) | frames..., frame = ciphertext length(4, big-endian) |
     * ciphertext || tag(16). The last frame carries final=1 (empty when the plaintext ended on a
     * boundary) so a file cut short is detected rather than read as complete.
     *
     * v1 files (no MAGIC) are still read by the legacy path below; the store refuses the ones too
     * large to buffer instead of running out of memory.
     */
    static final byte[] MAGIC_V2 = {'N', 'X', 'A', '2'};
    static final int FRAME_PLAINTEXT_BYTES = 1 << 20;
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

    public OutputStream encryptingStream(OutputStream sink, SecretKeySpec dataKey) throws IOException {
        byte[] prefix = new byte[NONCE_PREFIX_BYTES];
        secureRandom().nextBytes(prefix);
        sink.write(MAGIC_V2);
        sink.write(prefix);
        return new FrameEncryptingOutputStream(sink, dataKey, prefix);
    }

    /** Reads the format header from {@code source} and returns a stream that decrypts the remaining bytes under {@code dataKey}. */
    public InputStream decryptingStream(InputStream source, SecretKeySpec dataKey) throws IOException {
        byte[] head = source.readNBytes(MAGIC_V2.length);
        if (head.length != MAGIC_V2.length) {
            throw new IllegalArgumentException("encrypted artefact is too short to contain a nonce");
        }
        if (Arrays.equals(head, MAGIC_V2)) {
            byte[] prefix = source.readNBytes(NONCE_PREFIX_BYTES);
            if (prefix.length != NONCE_PREFIX_BYTES) {
                throw new IllegalArgumentException("encrypted artefact is too short to contain a nonce prefix");
            }
            return new FrameDecryptingInputStream(source, dataKey, prefix);
        }
        return legacyDecryptingStream(head, source, dataKey);
    }

    /** True when the first bytes of a stored file carry the v2 magic; a v1 file is decrypted whole, in memory. */
    public static boolean isStreamable(byte[] firstBytes) {
        return firstBytes != null && firstBytes.length >= MAGIC_V2.length
                && Arrays.equals(Arrays.copyOf(firstBytes, MAGIC_V2.length), MAGIC_V2);
    }

    private InputStream legacyDecryptingStream(byte[] nonceHead, InputStream source, SecretKeySpec dataKey)
            throws IOException {
        byte[] rest = source.readNBytes(NONCE_LENGTH_BYTES - nonceHead.length);
        if (rest.length != NONCE_LENGTH_BYTES - nonceHead.length) {
            throw new IllegalArgumentException("encrypted artefact is too short to contain a nonce");
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        System.arraycopy(nonceHead, 0, nonce, 0, nonceHead.length);
        System.arraycopy(rest, 0, nonce, nonceHead.length, rest.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new CipherInputStream(source, cipher);
        } catch (GeneralSecurityException e) {
            // Never embeds ciphertext or key material in the message (mirrors CredentialStoreCipher).
            throw new IllegalStateException("artefact store decryption failed", e);
        }
    }

    private static byte[] frameNonce(byte[] prefix, int index) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_BYTES);
        nonce[8] = (byte) (index >>> 24);
        nonce[9] = (byte) (index >>> 16);
        nonce[10] = (byte) (index >>> 8);
        nonce[11] = (byte) index;
        return nonce;
    }

    private static byte[] frameAad(int index, boolean last) {
        return new byte[] {(byte) (index >>> 24), (byte) (index >>> 16), (byte) (index >>> 8), (byte) index,
                (byte) (last ? 1 : 0)};
    }

    private static final class FrameEncryptingOutputStream extends OutputStream {
        private final OutputStream sink;
        private final SecretKeySpec dataKey;
        private final byte[] prefix;
        private final byte[] buffer = new byte[FRAME_PLAINTEXT_BYTES];
        private int buffered;
        private int frameIndex;
        private boolean closed;

        FrameEncryptingOutputStream(OutputStream sink, SecretKeySpec dataKey, byte[] prefix) {
            this.sink = sink;
            this.dataKey = dataKey;
            this.prefix = prefix;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int take = Math.min(len, buffer.length - buffered);
                System.arraycopy(b, off, buffer, buffered, take);
                buffered += take;
                off += take;
                len -= take;
                if (buffered == buffer.length) {
                    flushFrame(false);
                }
            }
        }

        private void flushFrame(boolean last) throws IOException {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, dataKey,
                        new GCMParameterSpec(TAG_LENGTH_BITS, frameNonce(prefix, frameIndex)));
                cipher.updateAAD(frameAad(frameIndex, last));
                byte[] ciphertext = cipher.doFinal(buffer, 0, buffered);
                sink.write(new byte[] {(byte) (ciphertext.length >>> 24), (byte) (ciphertext.length >>> 16),
                        (byte) (ciphertext.length >>> 8), (byte) ciphertext.length});
                sink.write(ciphertext);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("artefact store frame encryption failed", e);
            }
            frameIndex++;
            buffered = 0;
        }

        @Override
        public void flush() throws IOException {
            sink.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            flushFrame(true); // always a final frame, even an empty one: truncation is then detectable
            sink.close();
        }
    }

    private static final class FrameDecryptingInputStream extends InputStream {
        private final InputStream source;
        private final SecretKeySpec dataKey;
        private final byte[] prefix;
        private byte[] plain = new byte[0];
        private int position;
        private int frameIndex;
        private boolean finalSeen;

        FrameDecryptingInputStream(InputStream source, SecretKeySpec dataKey, byte[] prefix) {
            this.source = source;
            this.dataKey = dataKey;
            this.prefix = prefix;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (position >= plain.length) {
                if (finalSeen) {
                    return -1;
                }
                nextFrame();
            }
            int take = Math.min(len, plain.length - position);
            System.arraycopy(plain, position, b, off, take);
            position += take;
            return take;
        }

        private void nextFrame() throws IOException {
            byte[] lengthBytes = source.readNBytes(4);
            if (lengthBytes.length == 0) {
                throw new IOException("encrypted artefact is truncated: no final frame");
            }
            if (lengthBytes.length != 4) {
                throw new IOException("encrypted artefact is truncated inside a frame header");
            }
            int length = ((lengthBytes[0] & 0xff) << 24) | ((lengthBytes[1] & 0xff) << 16)
                    | ((lengthBytes[2] & 0xff) << 8) | (lengthBytes[3] & 0xff);
            if (length < TAG_LENGTH_BYTES || length > FRAME_PLAINTEXT_BYTES + TAG_LENGTH_BYTES) {
                throw new IOException("encrypted artefact frame length is out of range");
            }
            byte[] ciphertext = source.readNBytes(length);
            if (ciphertext.length != length) {
                throw new IOException("encrypted artefact is truncated inside a frame");
            }
            // The final flag is authenticated: a frame verifies under exactly one of the two AADs;
            // one that verifies under neither is tampered, reordered or under the wrong key.
            byte[] decrypted = tryDecrypt(ciphertext, false);
            boolean last = false;
            if (decrypted == null) {
                decrypted = tryDecrypt(ciphertext, true);
                last = true;
            }
            if (decrypted == null) {
                throw new IOException("encrypted artefact frame failed authentication");
            }
            plain = decrypted;
            position = 0;
            finalSeen = last;
            frameIndex++;
        }

        private byte[] tryDecrypt(byte[] ciphertext, boolean last) {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, dataKey,
                        new GCMParameterSpec(TAG_LENGTH_BITS, frameNonce(prefix, frameIndex)));
                cipher.updateAAD(frameAad(frameIndex, last));
                return cipher.doFinal(ciphertext);
            } catch (GeneralSecurityException e) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            source.close();
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
