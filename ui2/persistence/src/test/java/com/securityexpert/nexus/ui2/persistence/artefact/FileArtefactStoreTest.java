package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;

/**
 * BK-15 / C7 section 8: write-then-read round trips through the envelope,
 * criterion 3 (the master key alone cannot decrypt) and criterion 12 (a
 * re-wrap leaves ciphertext byte-identical and the artefact still
 * decryptable).
 */
class FileArtefactStoreTest {

    private static String randomKeyBase64() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void writeThenReadRoundTripsThroughTheEnvelope(@TempDir Path root) throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        FileArtefactStore store = new FileArtefactStore(root, cipher);
        byte[] plaintext = "show configuration\nset hostname gw-01\n".getBytes(StandardCharsets.UTF_8);

        ArtefactStore.ArtefactHandle handle = store.open("device-1", "job-1", "check_point", false);
        handle.sink().write(plaintext);
        ArtefactStore.ArtefactMetadata metadata = handle.finish();

        assertEquals(plaintext.length, metadata.plaintextBytes());
        assertNotEquals(0, metadata.wrappedDataKey().length);

        try (var in = store.retrieve(metadata.ref(), metadata.wrappedDataKey(), false)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }

    @Test
    void gzipRoundTripsThroughTheEnvelopeToo(@TempDir Path root) throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        FileArtefactStore store = new FileArtefactStore(root, cipher);
        byte[] plaintext = "<config><entry name=\"eth0\"/></config>".getBytes(StandardCharsets.UTF_8);

        ArtefactStore.ArtefactHandle handle = store.open("device-2", "job-2", "palo_alto", true);
        handle.sink().write(plaintext);
        ArtefactStore.ArtefactMetadata metadata = handle.finish();

        try (var in = store.retrieve(metadata.ref(), metadata.wrappedDataKey(), true)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }

    /** C7 section 8 criterion 3: no artefact is readable with the master key alone. */
    @Test
    void theWrappedDataKeyIsRequiredNotJustTheMasterKey(@TempDir Path root) throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        FileArtefactStore store = new FileArtefactStore(root, cipher);

        ArtefactStore.ArtefactHandle handle = store.open("device-3", "job-3", "check_point", false);
        handle.sink().write("secret-bearing configuration line".getBytes(StandardCharsets.UTF_8));
        ArtefactStore.ArtefactMetadata metadata = handle.finish();

        byte[] garbageWrappedKey = new byte[metadata.wrappedDataKey().length];
        assertThrows(IllegalStateException.class,
                () -> store.retrieve(metadata.ref(), garbageWrappedKey, false).readAllBytes(),
                "a store that only has the master key, without this artefact's own wrapped data key, must not decrypt it");
    }

    /** C7 section 8 criterion 12: a re-wrap leaves ciphertext byte-identical and the artefact still decryptable. */
    @Test
    void rewrappingTheDataKeyLeavesStoredCiphertextByteIdenticalAndStillDecryptable(@TempDir Path root)
            throws IOException {
        ArtefactStoreCipher oldCipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64(), "v1");
        ArtefactStoreCipher newCipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64(), "v2");
        FileArtefactStore store = new FileArtefactStore(root, oldCipher);
        byte[] plaintext = "raw device configuration, never re-encrypted on rotation".getBytes(StandardCharsets.UTF_8);

        ArtefactStore.ArtefactHandle handle = store.open("device-4", "job-4", "check_point", false);
        handle.sink().write(plaintext);
        ArtefactStore.ArtefactMetadata metadata = handle.finish();

        Path storedFile = root.resolve(metadata.ref().value());
        byte[] ciphertextBeforeRewrap = Files.readAllBytes(storedFile);

        byte[] wrappedUnderNew = oldCipher.rewrap(metadata.wrappedDataKey(), newCipher);

        byte[] ciphertextAfterRewrap = Files.readAllBytes(storedFile);
        assertArrayEquals(ciphertextBeforeRewrap, ciphertextAfterRewrap,
                "a re-wrap must never touch the stored ciphertext bytes");

        FileArtefactStore storeUnderNewKey = new FileArtefactStore(root, newCipher);
        try (var in = storeUnderNewKey.retrieve(metadata.ref(), wrappedUnderNew, false)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }

    /** A v1 (pre-streaming) file above the in-memory budget is refused with a reason, never decrypted into an OutOfMemoryError. */
    @Test
    void aLargePreStreamingFileIsRefusedWithAReason(@TempDir Path root) throws Exception {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        FileArtefactStore store = new FileArtefactStore(root, cipher, 64);
        javax.crypto.spec.SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Path legacy = root.resolve("check_point").resolve("dev").resolve("legacy.enc");
        java.nio.file.Files.createDirectories(legacy.getParent());
        try (var file = java.nio.file.Files.newOutputStream(legacy)) {
            file.write(nonce);
            javax.crypto.Cipher gcm = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(javax.crypto.Cipher.ENCRYPT_MODE, dataKey, new javax.crypto.spec.GCMParameterSpec(128, nonce));
            try (var out = new javax.crypto.CipherOutputStream(file, gcm)) {
                out.write(new byte[200]);
            }
        }

        java.io.IOException refused = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> store.retrieve(new ArtefactRef("check_point/dev/legacy.enc"), cipher.wrapKey(dataKey), false));
        org.junit.jupiter.api.Assertions.assertTrue(refused.getMessage().contains("pre-streaming"), refused.getMessage());
    }
}
