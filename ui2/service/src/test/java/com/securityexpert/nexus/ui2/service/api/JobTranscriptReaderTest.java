package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;

class JobTranscriptReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LINES = "{\"seq\":1,\"text\":\"synthetic first\"}\r\n\r\n{\"seq\":2,\"text\":\"synthetic second\"}\r\n";

    private static ArtefactStore.ArtefactMetadata write(FileArtefactStore store, String content, boolean gzip) throws Exception {
        try (var handle = store.open("synthetic-device", "synthetic-job", "transcript", gzip)) {
            handle.sink().write(content.getBytes(StandardCharsets.UTF_8));
            return handle.finish();
        }
    }

    @Test
    void readsBomCrlfAndArraysWithOrWithoutGzip(@TempDir Path root) throws Exception {
        var cipher = ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        var store = new FileArtefactStore(root, cipher);
        var reader = new JobTranscriptReader(JSON);
        for (boolean gzip : List.of(false, true)) {
            for (String content : List.of(LINES, "\uFEFF" + LINES,
                    "\uFEFF[\r\n{\"seq\":1,\"text\":\"synthetic first\"},\r\n{\"seq\":2,\"text\":\"synthetic second\"}\r\n]")) {
                var ref = write(store, content, gzip);
                var transcript = reader.read(store, ref.ref(), ref.wrappedDataKey());
                assertEquals(2, transcript.entries().size());
                assertEquals(content.contains("[") ? "JSON_ARRAY" : "JSON_LINES", transcript.format());
                assertEquals("synthetic second", transcript.entries().get(1).get("text").asText());
            }
        }
    }

    @Test
    void readsLegacyCipherEnvelopeAndRejectsTheWrongCompressionAssumption(@TempDir Path root) throws Exception {
        var cipher = ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        var store = new FileArtefactStore(root, cipher);
        var key = cipher.generateDataKey();
        var nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        var gcm = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, nonce));
        var ref = new ArtefactRef("legacy.enc");
        try (var output = Files.newOutputStream(root.resolve(ref.value()))) {
            output.write(nonce);
            output.write(gcm.doFinal(LINES.getBytes(StandardCharsets.UTF_8)));
        }
        var wrapped = cipher.wrapKey(key);
        assertEquals(2, new JobTranscriptReader(JSON).read(store, ref, wrapped).entries().size());
        assertThrows(java.util.zip.ZipException.class, () -> {
            try (var ignored = store.retrieve(ref, wrapped, true)) { }
        });
    }

    @Test
    void capsExpandedPayloadAndChecksAllCipherFrames(@TempDir Path root) throws Exception {
        var cipher = ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        var store = new FileArtefactStore(root, cipher);
        for (boolean gzip : List.of(false, true)) {
            var ref = write(store, "{\"text\":\"" + "x".repeat(1024) + "\"}", gzip);
            var failure = assertThrows(JobTranscriptReader.ReadFailure.class,
                    () -> new JobTranscriptReader(JSON, 128).read(store, ref.ref(), ref.wrappedDataKey()));
            assertEquals("TRANSCRIPT_PARSE_FAILED", failure.code());
            assertTrue(failure.decrypts());
        }
        // A multi-frame payload with a valid prefix must not succeed when its authenticated tail is damaged.
        var ref = write(store, "{\"text\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}", false);
        var path = root.resolve(ref.ref().value());
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);
        var failure = assertThrows(JobTranscriptReader.ReadFailure.class,
                () -> new JobTranscriptReader(JSON).read(store, ref.ref(), ref.wrappedDataKey()));
        assertEquals("TRANSCRIPT_DECRYPT_FAILED", failure.code());
        assertFalse(failure.decrypts());
    }
}
