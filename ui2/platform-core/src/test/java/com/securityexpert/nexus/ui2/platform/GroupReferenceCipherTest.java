package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class GroupReferenceCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripsAGroupReference() {
        GroupReferenceCipher cipher = GroupReferenceCipher.fromBase64Key(randomKey());
        String groupReference = "cn=backup-admins,ou=groups,dc=example,dc=com";

        byte[] encrypted = cipher.encrypt(groupReference);
        assertFalse(new String(encrypted).contains(groupReference), "ciphertext must never contain the plaintext");

        assertEquals(groupReference, cipher.decrypt(encrypted));
    }

    @Test
    void encryptingTheSameValueTwiceProducesDifferentCiphertext() {
        GroupReferenceCipher cipher = GroupReferenceCipher.fromBase64Key(randomKey());
        String groupReference = "cn=ops,dc=example,dc=com";

        byte[] first = cipher.encrypt(groupReference);
        byte[] second = cipher.encrypt(groupReference);

        assertFalse(Arrays.equals(first, second), "a fresh random nonce must make each encryption distinct");
        assertEquals(groupReference, cipher.decrypt(first));
        assertEquals(groupReference, cipher.decrypt(second));
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        GroupReferenceCipher cipherA = GroupReferenceCipher.fromBase64Key(randomKey());
        GroupReferenceCipher cipherB = GroupReferenceCipher.fromBase64Key(randomKey());
        byte[] encrypted = cipherA.encrypt("cn=ops,dc=example,dc=com");

        assertNotEquals("cn=ops,dc=example,dc=com", tryDecryptOrSentinel(cipherB, encrypted));
    }

    private static String tryDecryptOrSentinel(GroupReferenceCipher cipher, byte[] encrypted) {
        try {
            return cipher.decrypt(encrypted);
        } catch (RuntimeException e) {
            return "<decryption failed>";
        }
    }
}
