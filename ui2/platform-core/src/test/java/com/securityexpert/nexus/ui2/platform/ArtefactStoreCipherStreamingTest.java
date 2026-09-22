package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * Bulk format v2 (framed AES-GCM): decrypts in bounded memory, fails closed on
 * truncation or reordering, and still reads a v1 file.
 */
class ArtefactStoreCipherStreamingTest {

    private static ArtefactStoreCipher cipher() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(raw));
    }

    private static byte[] encrypt(ArtefactStoreCipher cipher, SecretKeySpec dataKey, byte[] plaintext) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (var out = cipher.encryptingStream(sink, dataKey)) {
            out.write(plaintext);
        }
        return sink.toByteArray();
    }

    @Test
    void roundTripsAcrossSeveralFramesAndAFrameBoundary() throws IOException {
        ArtefactStoreCipher cipher = cipher();
        SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] plaintext = new byte[2 * ArtefactStoreCipher.FRAME_PLAINTEXT_BYTES]; // exactly two frames + an empty final one
        new SecureRandom().nextBytes(plaintext);

        byte[] ciphertext = encrypt(cipher, dataKey, plaintext);

        assertTrue(ArtefactStoreCipher.isStreamable(Arrays.copyOf(ciphertext, 4)));
        try (InputStream in = cipher.decryptingStream(new ByteArrayInputStream(ciphertext), dataKey)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }

    @Test
    void anEmptyPlaintextStillCarriesAFinalFrame() throws IOException {
        ArtefactStoreCipher cipher = cipher();
        SecretKeySpec dataKey = cipher.generateDataKey();

        byte[] ciphertext = encrypt(cipher, dataKey, new byte[0]);

        try (InputStream in = cipher.decryptingStream(new ByteArrayInputStream(ciphertext), dataKey)) {
            assertEquals(0, in.readAllBytes().length);
        }
    }

    @Test
    void aTruncatedFileFailsClosedInsteadOfReadingAsComplete() throws IOException {
        ArtefactStoreCipher cipher = cipher();
        SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] plaintext = new byte[ArtefactStoreCipher.FRAME_PLAINTEXT_BYTES + 10];
        byte[] ciphertext = encrypt(cipher, dataKey, plaintext);

        // Drop the final frame entirely: the first frame still verifies, the end is then missing.
        int firstFrameEnd = 4 + 8 + 4 + ArtefactStoreCipher.FRAME_PLAINTEXT_BYTES + 16;
        byte[] cut = Arrays.copyOf(ciphertext, firstFrameEnd);
        assertThrows(IOException.class, () -> {
            try (InputStream in = cipher.decryptingStream(new ByteArrayInputStream(cut), dataKey)) {
                in.readAllBytes();
            }
        });
    }

    @Test
    void aReorderedFrameFailsAuthentication() throws IOException {
        ArtefactStoreCipher cipher = cipher();
        SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] plaintext = new byte[2 * ArtefactStoreCipher.FRAME_PLAINTEXT_BYTES];
        new SecureRandom().nextBytes(plaintext);
        byte[] ciphertext = encrypt(cipher, dataKey, plaintext);

        int frame = 4 + ArtefactStoreCipher.FRAME_PLAINTEXT_BYTES + 16;
        int header = 12;
        byte[] swapped = ciphertext.clone();
        System.arraycopy(ciphertext, header + frame, swapped, header, frame);
        System.arraycopy(ciphertext, header, swapped, header + frame, frame);
        assertThrows(IOException.class, () -> {
            try (InputStream in = cipher.decryptingStream(new ByteArrayInputStream(swapped), dataKey)) {
                in.readAllBytes();
            }
        });
    }

    @Test
    void aV1FileWrittenBeforeTheFramedFormatStillDecrypts() throws IOException, GeneralSecurityException {
        ArtefactStoreCipher cipher = cipher();
        SecretKeySpec dataKey = cipher.generateDataKey();
        byte[] plaintext = "stored on 2026-09-21, one nonce, one stream".getBytes();

        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        sink.write(nonce);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(128, nonce));
        try (var out = new CipherOutputStream(sink, gcm)) {
            out.write(plaintext);
        }
        byte[] v1 = sink.toByteArray();

        assertFalse(ArtefactStoreCipher.isStreamable(Arrays.copyOf(v1, 4)));
        try (InputStream in = cipher.decryptingStream(new ByteArrayInputStream(v1), dataKey)) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
    }
}
