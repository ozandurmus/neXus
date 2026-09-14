package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** 2026-09-14 PO decision record CS-2/AC-3. */
class CredentialStoreCipherTest {

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptThenDecryptRoundTripsThePlaintext() {
        CredentialStoreCipher cipher = CredentialStoreCipher.fromBase64Key(randomBase64Key());
        String plaintext = "correct-horse-battery-staple-CS-2-fixture";

        byte[] wire = cipher.encrypt(plaintext);

        assertFalse(new String(wire).contains(plaintext), "the wire form must not contain the plaintext");
        assertEquals(plaintext, cipher.decrypt(wire));
    }

    @Test
    void decryptionWithADifferentKeyFailsClosed() {
        CredentialStoreCipher writer = CredentialStoreCipher.fromBase64Key(randomBase64Key());
        CredentialStoreCipher reader = CredentialStoreCipher.fromBase64Key(randomBase64Key());
        byte[] wire = writer.encrypt("a secret only the writer's key should ever open");

        assertThrows(IllegalStateException.class, () -> reader.decrypt(wire));
    }

    @Test
    void aKeyThatDoesNotDecodeTo32BytesIsRejected() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalArgumentException.class, () -> CredentialStoreCipher.fromBase64Key(tooShort));
    }

    @Test
    void everyEncryptCallUsesAFreshNonceEvenForTheSamePlaintext() {
        CredentialStoreCipher cipher = CredentialStoreCipher.fromBase64Key(randomBase64Key());
        String plaintext = "same plaintext, twice";

        byte[] first = cipher.encrypt(plaintext);
        byte[] second = cipher.encrypt(plaintext);

        assertFalse(java.util.Arrays.equals(first, second), "identical plaintext must not produce identical ciphertext");
    }
}
