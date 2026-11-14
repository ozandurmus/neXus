package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void defaultQuotaIsSafeShareOfVolumeAndConfigurableViaEnvOrFactory(@TempDir Path root) {
        long halfTiB = 500L * 1024L * 1024L * 1024L;
        assertEquals(200L * 1024L * 1024L * 1024L, FileArtefactStore.defaultQuotaBytes(halfTiB),
                "40% of a 500 GiB volume is 200 GiB");
        long twoTiB = 2048L * 1024L * 1024L * 1024L;
        assertEquals(FileArtefactStore.DEFAULT_MAX_QUOTA_BYTES, FileArtefactStore.defaultQuotaBytes(twoTiB),
                "default volume share is capped at the 400 GiB recovery volume budget");

        assertEquals(1_048_576L,
                FileArtefactStore.resolveQuotaBytes(root, java.util.Map.of(FileArtefactStore.QUOTA_ENV_VAR, "1048576")));
        // One variable only: an unrelated name is not read.
        assertEquals(FileArtefactStore.defaultQuotaBytes(root),
                FileArtefactStore.resolveQuotaBytes(root, java.util.Map.of("UI2_ARTEFACT_STORE_MAX_BYTES", "2097152")));
        assertThrows(IllegalStateException.class,
                () -> FileArtefactStore.resolveQuotaBytes(root, java.util.Map.of(FileArtefactStore.QUOTA_ENV_VAR, "0")));
        assertThrows(IllegalStateException.class,
                () -> FileArtefactStore.resolveQuotaBytes(root, java.util.Map.of(FileArtefactStore.QUOTA_ENV_VAR, "not-a-number")));

        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        FileArtefactStore configured = FileArtefactStore.withQuota(root, cipher, 4096L);
        assertEquals(4096L, configured.quotaBytes());
    }

    @Test
    void refusesWriteBeforeOpeningWhenQuotaAlreadyReachedAndPreservesExistingBackups(@TempDir Path root) throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        byte[] firstBackup = "first-backup-archive-bytes".getBytes(StandardCharsets.UTF_8);

        // Measure the exact ciphertext size of the first backup, then set quota equal to that size.
        FileArtefactStore probe = new FileArtefactStore(root.resolve("probe"), cipher);
        ArtefactStore.ArtefactMetadata probeMeta;
        try (ArtefactStore.ArtefactHandle h = probe.open("device-1", "job-probe", "check_point", false)) {
            h.sink().write(firstBackup);
            probeMeta = h.finish();
        }

        Path storeRoot = root.resolve("store");
        FileArtefactStore store = FileArtefactStore.withQuota(storeRoot, cipher, probeMeta.ciphertextBytes());
        ArtefactStore.ArtefactMetadata existingMeta;
        try (ArtefactStore.ArtefactHandle h = store.open("device-1", "job-1", "check_point", false)) {
            h.sink().write(firstBackup);
            existingMeta = h.finish();
        }
        assertEquals(probeMeta.ciphertextBytes(), store.usedBytes());

        FileArtefactStore.QuotaExceededException checkEx = assertThrows(
                FileArtefactStore.QuotaExceededException.class,
                store::checkQuota);
        org.junit.jupiter.api.Assertions.assertTrue(
                checkEx.getMessage().startsWith(FileArtefactStore.QUOTA_EXCEEDED_REASON),
                checkEx.getMessage());

        FileArtefactStore.QuotaExceededException openEx = assertThrows(
                FileArtefactStore.QuotaExceededException.class,
                () -> store.open("device-1", "job-2", "check_point", false));
        org.junit.jupiter.api.Assertions.assertTrue(
                openEx.getMessage().startsWith(FileArtefactStore.QUOTA_EXCEEDED_REASON),
                openEx.getMessage());

        try (var walk = Files.walk(storeRoot)) {
            java.util.List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size(), "only the pre-existing backup file may exist; no .tmp file may be created");
            org.junit.jupiter.api.Assertions.assertFalse(files.get(0).toString().endsWith(".tmp"));
        }
        try (var in = store.retrieve(existingMeta.ref(), existingMeta.wrappedDataKey(), false)) {
            assertArrayEquals(firstBackup, in.readAllBytes(), "existing backup must never be deleted or corrupted");
        }
    }

    @Test
    void refusesStreamingWriteThatExceedsQuotaWithoutLeavingPartialFilesOrDeletingExistingBackups(@TempDir Path root)
            throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        byte[] firstBackup = "existing-backup-payload".getBytes(StandardCharsets.UTF_8);

        // Allow the first backup plus 32 extra bytes, then stream a second backup that exceeds the remaining budget.
        FileArtefactStore probe = new FileArtefactStore(root.resolve("probe"), cipher);
        long firstCiphertextBytes;
        try (ArtefactStore.ArtefactHandle h = probe.open("device-1", "job-probe", "check_point", false)) {
            h.sink().write(firstBackup);
            firstCiphertextBytes = h.finish().ciphertextBytes();
        }

        Path storeRoot = root.resolve("store");
        FileArtefactStore store = FileArtefactStore.withQuota(storeRoot, cipher, firstCiphertextBytes + 32L);
        ArtefactStore.ArtefactMetadata firstMeta;
        try (ArtefactStore.ArtefactHandle h = store.open("device-1", "job-1", "check_point", false)) {
            h.sink().write(firstBackup);
            firstMeta = h.finish();
        }

        byte[] oversizedSecondBackup = new byte[4096];
        new SecureRandom().nextBytes(oversizedSecondBackup);
        FileArtefactStore.QuotaExceededException streamEx = assertThrows(
                FileArtefactStore.QuotaExceededException.class, () -> {
                    try (ArtefactStore.ArtefactHandle h = store.open("device-1", "job-2", "check_point", false)) {
                        h.sink().write(oversizedSecondBackup);
                        h.finish();
                    }
                });
        org.junit.jupiter.api.Assertions.assertTrue(
                streamEx.getMessage().startsWith(FileArtefactStore.QUOTA_EXCEEDED_REASON),
                streamEx.getMessage());

        try (var walk = Files.walk(storeRoot)) {
            java.util.List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size(), "refused write must leave zero .tmp or partial .enc files behind");
            assertEquals(storeRoot.resolve(firstMeta.ref().value()), files.get(0));
        }
        try (var in = store.retrieve(firstMeta.ref(), firstMeta.wrappedDataKey(), false)) {
            assertArrayEquals(firstBackup, in.readAllBytes(), "existing backup must be preserved untouched");
        }
    }

    @Test
    void aQuotaRefusalIsFoundWrappedInACauseChainOrFlattenedIntoAReason() {
        var refusal = new ArtefactStore.QuotaExceededException(90L, 100L, 20L);
        assertTrue(refusal.getMessage().startsWith(ArtefactStore.QUOTA_EXCEEDED_REASON + ": "));
        assertEquals(java.util.Optional.of(refusal.getMessage()),
                ArtefactStore.QuotaExceededException.reasonIn(new RuntimeException("sftp", new java.io.UncheckedIOException(refusal))));
        assertEquals(java.util.Optional.of(refusal.getMessage()),
                ArtefactStore.QuotaExceededException.reasonIn("download failed: " + refusal.getMessage()));
        assertEquals(java.util.Optional.empty(), ArtefactStore.QuotaExceededException.reasonIn("connect timed out"));
        assertEquals(java.util.Optional.empty(), ArtefactStore.QuotaExceededException.reasonIn((String) null));
        assertEquals(java.util.Optional.empty(), ArtefactStore.QuotaExceededException.reasonIn(new IllegalStateException()));
    }

    @Test
    void nearLimitWarningTriggersAtEightyPercentOfQuota(@TempDir Path root) throws IOException {
        ArtefactStoreCipher cipher = ArtefactStoreCipher.fromBase64Key(randomKeyBase64());
        byte[] payload = new byte[256];
        new SecureRandom().nextBytes(payload);

        FileArtefactStore probe = new FileArtefactStore(root.resolve("probe"), cipher);
        long oneArtefactBytes;
        try (ArtefactStore.ArtefactHandle h = probe.open("device-1", "job-probe", "check_point", false)) {
            h.sink().write(payload);
            oneArtefactBytes = h.finish().ciphertextBytes();
        }

        // Quota = 2 artefacts; 1 artefact = 50% (below 80% threshold), 2 artefacts = 100% (>= 80% threshold).
        FileArtefactStore store = FileArtefactStore.withQuota(root.resolve("store"), cipher, oneArtefactBytes * 2L);
        org.junit.jupiter.api.Assertions.assertFalse(store.isNearLimit());

        try (ArtefactStore.ArtefactHandle h = store.open("device-1", "job-1", "check_point", false)) {
            h.sink().write(payload);
            h.finish();
        }
        org.junit.jupiter.api.Assertions.assertFalse(store.isNearLimit(), "50% usage is below the 80% warning threshold");

        byte[] secondPayload = new byte[256];
        new SecureRandom().nextBytes(secondPayload);
        try (ArtefactStore.ArtefactHandle h = store.open("device-1", "job-2", "check_point", false)) {
            h.sink().write(secondPayload);
            h.finish();
        }
        org.junit.jupiter.api.Assertions.assertTrue(store.isNearLimit(), ">= 80% usage must trigger near-limit warning");
    }
}
