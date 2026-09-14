package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * BK-15 / C7 section 4.1 and 4.3: per-artefact data keys wrapped by the
 * master key, and the pure re-wrap (rotation) operation.
 */
class ArtefactStoreCipherTest {

    private static String randomKeyBase64() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTripsPlaintextThroughAFreshDataKey() throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] plaintext = "the quick brown fox jumps over the lazy dog".getBytes();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (var out = cipher.encryptingStream(sink, dataKey)) {
            out.write(plaintext);
        }

        try (var in = cipher.decryptingStream(new ByteArrayInputStream(sink.toByteArray()), dataKey)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }

    @Test
    void wrappedDataKeyRoundTripsUnderTheSameMasterKey() {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        SecretKeySpec dataKey = cipher.generateDataKey();

        byte[] wrapped = cipher.wrapKey(dataKey);

        assertNotEquals(Base64.getEncoder().encodeToString(dataKey.getEncoded()),
                Base64.getEncoder().encodeToString(wrapped), "the wrapped form must never equal the raw data key");
        SecretKeySpec unwrapped = cipher.unwrapKey(wrapped);
        assertArrayEquals(dataKey.getEncoded(), unwrapped.getEncoded());
    }

    /** C7 section 8 criterion 3: the master key alone does not decrypt an artefact -- the wrapped data key is required. */
    @Test
    void theMasterKeyAloneCannotDecryptAnArtefactWithoutUnwrappingItsDataKey() throws IOException {
        String masterKeyBase64 = randomKeyBase64();
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(masterKeyBase64);
        SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] plaintext = "raw configuration bytes".getBytes();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (var out = cipher.encryptingStream(sink, dataKey)) {
            out.write(plaintext);
        }
        byte[] ciphertext = sink.toByteArray();

        // Attempting to decrypt directly with the master key's own bytes -- skipping
        // unwrapKey() entirely, as "the master key alone" means -- fails closed: the
        // master key is never the key that encrypted these bytes.
        SecretKeySpec masterKeyAsIfItWereTheDataKey =
                new SecretKeySpec(Base64.getDecoder().decode(masterKeyBase64), "AES");
        assertThrows(IOException.class,
                () -> cipher.decryptingStream(new ByteArrayInputStream(ciphertext), masterKeyAsIfItWereTheDataKey)
                        .readAllBytes(),
                "GCM tag verification must fail closed when the key used to decrypt was never the key that "
                        + "encrypted these bytes");

        // The wrapped data key, unwrapped, is what actually recovers the plaintext.
        try (var in = cipher.decryptingStream(new ByteArrayInputStream(ciphertext),
                cipher.unwrapKey(cipher.wrapKey(dataKey)))) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }

    /** C7 section 8 criterion 12: a re-wrap never touches ciphertext and the artefact stays decryptable under the new key. */
    @Test
    void rewrapChangesOnlyTheWrappedKeyNeverTheArtefactBytes() throws IOException {
        ArtefactStoreCipher oldCipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64(), "v1");
        ArtefactStoreCipher newCipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64(), "v2");
        SecretKeySpec dataKey = oldCipher.generateDataKey();
        byte[] plaintext = "raw configuration bytes, never re-encrypted on rotation".getBytes();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (var out = oldCipher.encryptingStream(sink, dataKey)) {
            out.write(plaintext);
        }
        byte[] ciphertextBeforeRewrap = sink.toByteArray();
        byte[] wrappedUnderOld = oldCipher.wrapKey(dataKey);

        byte[] wrappedUnderNew = oldCipher.rewrap(wrappedUnderOld, newCipher);

        assertEquals("v2", newCipher.keyId());
        assertNotEquals(Base64.getEncoder().encodeToString(wrappedUnderOld),
                Base64.getEncoder().encodeToString(wrappedUnderNew));

        // The artefact bytes captured before the rewrap are untouched by it (the rewrap
        // operates only on the small wrapped-key value); decrypting those same bytes with
        // the data key recovered from the new wrapped form must still recover the plaintext.
        SecretKeySpec recoveredDataKey = newCipher.unwrapKey(wrappedUnderNew);
        assertArrayEquals(dataKey.getEncoded(), recoveredDataKey.getEncoded());
        try (var in = newCipher.decryptingStream(new ByteArrayInputStream(ciphertextBeforeRewrap), recoveredDataKey)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }

        // The old master key can no longer unwrap the new wrapped form.
        assertThrows(IllegalStateException.class, () -> oldCipher.unwrapKey(wrappedUnderNew));
    }

    @Test
    void unwrapFailsClosedUnderTheWrongMasterKey() {
        ArtefactStoreCipher cipherA = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        ArtefactStoreCipher cipherB = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        byte[] wrapped = cipherA.wrapKey(cipherA.generateDataKey());

        assertThrows(IllegalStateException.class, () -> cipherB.unwrapKey(wrapped));
    }

    @Test
    void defaultKeyIdIsV1() {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        assertEquals("v1", cipher.keyId());
        assertEquals(ArtefactStoreCipher.KEY_ID, cipher.keyId());
    }

    @Test
    void rejectsAKeyThatIsNotThirtyTwoBytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> ArtefactStoreCipher.fromBase64Key(shortKey));
    }
}
