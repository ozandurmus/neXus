package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;

class BackupEmptyStatusTest {
    private static final String ARCHIVE = "backup_fw-tango-04_20260928.tgz";

    private static BackupRequest request() {
        return new BackupRequest(new ConnectionTarget("endpoint-1", "192.0.2.10", 22),
                Optional.of("credential-1"), "test-trust");
    }

    private static BackupCapabilityExecutor executor(ScriptedBackupTransport transport, Path dir,
            AtomicReference<Instant> clock) {
        var store = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32])));
        return new BackupCapabilityExecutor(transport, store, 1L, Duration.ofMinutes(1), Duration.ofMinutes(30),
                null, clock::get, duration -> clock.updateAndGet(time -> time.plus(duration)));
    }

    @Test
    void continuousEmptyStatusRefusesAfterThreeMinutesWithoutDelete(@TempDir Path dir) {
        var transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execOutputs.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL,
                "Backup refused on fw-tango-04, code 329\nArchive " + ARCHIVE + "\nignored third line\n");
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        BackupResult result = executor(transport, dir, clock).collect(request(), "device-1", "job-1");

        var refused = assertInstanceOf(BackupResult.SubmitRefused.class, result);
        assertTrue(refused.reason().contains("the device did not start a backup"));
        assertTrue(refused.reason().contains("Backup refused on <host>, code #"), refused.reason());
        assertFalse(refused.reason().contains("ignored third line"));
        assertFalse(refused.reason().contains(ARCHIVE));
        assertFalse(refused.reason().matches(".*\\d.*"), refused.reason());
        assertEquals(3, Duration.between(Instant.parse("2026-01-01T00:00:00Z"), clock.get()).toMinutes());
        assertEquals(5, transport.statusCallCount); // initial plain read, its retry, then three login-shell polls
        assertEquals(1, transport.commandsIssued.stream().filter(BackupReadPlan.CP_SHOW_BACKUP_STATUS::equals).count());
        assertFalse(transport.commandsIssued.stream().anyMatch(c -> c.contains("delete backup")));
    }

    @Test
    void statusContentAfterOneMinuteKeepsSuccessPath(@TempDir Path dir) throws Exception {
        var transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execOutputs.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL, "Creating backup package\n");
        transport.statusSequence.add("");
        transport.statusSequence.add("in progress");
        transport.statusSequence.add("succeeded; backup file location: /var/log/CPbackup/backups/" + ARCHIVE);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(transport.fetchedContent.getBytes(StandardCharsets.UTF_8));
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand("/var/log/CPbackup/backups/" + ARCHIVE),
                java.util.HexFormat.of().formatHex(digest) + "  " + ARCHIVE);
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        BackupResult result = executor(transport, dir, clock).collect(request(), "device-1", "job-1");

        assertInstanceOf(BackupResult.Completed.class, result);
        assertEquals(3, transport.statusCallCount);
        assertTrue(transport.commandsIssued.contains(BackupReadPlan.deleteBackupCommand(ARCHIVE)));
    }
    @Test
    void emptyPlainStatusRetriesOnceThenKeepsLoginShellForThisRun(@TempDir Path dir) throws Exception {
        var transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execOutputs.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL, "Creating backup package...\n");
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_BACKUP_STATUS, "");
        transport.statusSequence.add("in progress");
        transport.statusSequence.add("succeeded; backup file location: /var/log/CPbackup/backups/" + ARCHIVE);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(transport.fetchedContent.getBytes(StandardCharsets.UTF_8));
        transport.execOutputs.put(BackupReadPlan.archiveDigestCommand("/var/log/CPbackup/backups/" + ARCHIVE),
                java.util.HexFormat.of().formatHex(digest) + "  " + ARCHIVE);
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var executor = executor(transport, dir, clock);

        assertInstanceOf(BackupResult.Completed.class, executor.collect(request(), "device-1", "job-1"));
        String loginPoll = "bash -lc '" + BackupReadPlan.CP_SHOW_BACKUP_STATUS + "'";
        assertEquals(1, transport.commandsIssued.stream().filter(BackupReadPlan.CP_SHOW_BACKUP_STATUS::equals).count());
        assertEquals(2, transport.commandsIssued.stream().filter(loginPoll::equals).count());
        assertEquals(Duration.ofMinutes(1), Duration.between(Instant.parse("2026-01-01T00:00:00Z"), clock.get()));
        assertTrue(transport.commandsIssued.contains(BackupReadPlan.deleteBackupCommand(ARCHIVE)));

        transport.commandsIssued.clear();
        transport.execOutputs.remove(BackupReadPlan.CP_SHOW_BACKUP_STATUS);
        assertInstanceOf(BackupResult.Completed.class, executor.collect(request(), "device-1", "job-2"));
        assertTrue(transport.commandsIssued.contains(BackupReadPlan.CP_SHOW_BACKUP_STATUS));
        assertFalse(transport.commandsIssued.contains(loginPoll), "login-shell choice must not leak between runs");
    }

    @Test
    void emptySubmitRetriesOnceAndPollsInLoginShell(@TempDir Path dir) {
        var transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        String loginSubmit = "bash -lc '" + BackupReadPlan.CP_ADD_BACKUP_LOCAL + "'";
        transport.execOutputs.put(loginSubmit, "Creating backup package...");
        transport.statusSequence.add("failed");
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        var refused = assertInstanceOf(BackupResult.SubmitRefused.class,
                executor(transport, dir, clock).collect(request(), "device-1", "job-1"));
        assertTrue(refused.reason().contains("show backup status reported a failed backup"));
        assertEquals(1, transport.commandsIssued.stream().filter(BackupReadPlan.CP_ADD_BACKUP_LOCAL::equals).count());
        assertEquals(1, transport.commandsIssued.stream().filter(loginSubmit::equals).count());
        assertFalse(transport.commandsIssued.contains(BackupReadPlan.CP_SHOW_BACKUP_STATUS));
        assertTrue(transport.commandsIssued.contains("bash -lc '" + BackupReadPlan.CP_SHOW_BACKUP_STATUS + "'"));
    }

    @Test
    void unsuccessfulEmptySubmitIsNotRetried(@TempDir Path dir) {
        var transport = new ScriptedBackupTransport();
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "1000000\n");
        transport.execExitStatus.put(BackupReadPlan.CP_ADD_BACKUP_LOCAL, 1);
        var clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        assertInstanceOf(BackupResult.SubmitRefused.class,
                executor(transport, dir, clock).collect(request(), "device-1", "job-1"));
        assertFalse(transport.commandsIssued.stream().anyMatch(c -> c.startsWith("bash -lc")));
    }

}
