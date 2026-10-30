package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * BK-16 / BK-18 (migration V17), proved without a live PostgreSQL instance
 * -- the same jOOQ {@code MockDataProvider} pattern {@code
 * JooqDeviceInventoryRepositoryTest} already establishes.
 */
class JooqBackupArtefactManifestRepositoryTest {

    private static BackupArtefactManifestRecord sampleRecord(String vendor, Optional<String> softwareVersion) {
        return sampleRecord(vendor, softwareVersion, Optional.empty());
    }

    private static BackupArtefactManifestRecord sampleRecord(String vendor, Optional<String> softwareVersion,
            Optional<Instant> expiresAt) {
        return new BackupArtefactManifestRecord("artefact-1", "device-1", Optional.empty(),
                ArtefactClass.CONFIGURATION, vendor, softwareVersion, "a".repeat(64), "plain-sha", 100L,
                "cipher-sha", 132L, "v1", new byte[] { 1, 2, 3 }, ArtefactValidation.reachedWithoutRestore("V1"),
                "standard", expiresAt, "/app/artefact-store", Optional.empty());
    }

    @Test
    void aNewArtefactWithoutExpiryWritesNoRetentionWarning() {
        var messages = new ArrayList<String>();
        var logger = Logger.getLogger(JooqBackupArtefactManifestRepository.class.getName());
        var handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        var dsl = DSL.using(new MockConnection(ctx -> new MockResult[] {new MockResult(1, null)}), SQLDialect.POSTGRES);
        var repository = new JooqBackupArtefactManifestRepository(new JooqTransactionBoundary(dsl));
        logger.addHandler(handler);
        try {
            repository.record(sampleRecord("palo_alto", Optional.empty()), "system:worker", "backup_artefact_recorded");
            repository.record(sampleRecord("palo_alto", Optional.empty(), Optional.of(Instant.parse("2026-10-20T00:00:00Z"))),
                    "system:worker", "backup_artefact_recorded");
            // Retention follows the policy row, not expires_at (C7 Amendment A-2, PO decision 2026-10-18).
            assertEquals(List.of(), messages);
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void recordInsertsTheManifestRowAndTheLedgerEventInOneAuditedTransaction() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(1, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        JooqBackupArtefactManifestRepository repository = new JooqBackupArtefactManifestRepository(transactionBoundary);

        repository.record(sampleRecord("palo_alto", Optional.empty()), "system:worker", "backup_artefact_recorded");

        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("insert into backup_artefact")));
        assertEquals(1, executedSql.stream().filter(sql -> sql.contains("insert into artefact_retention_ledger")).count());
        assertTrue(executedSql.get(0).contains("SET LOCAL app.actor_fingerprint"),
                "the audit context is set before any row is written");
    }

    /** C7 section 8 criterion for section 3.3: zero rows, named failure -- proved by never reaching the repository at all. */
    @Test
    void aCheckPointArtefactWithNoResolvableSoftwareVersionRefusesBeforeAnyRowIsWritten() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(1, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqBackupArtefactManifestRepository repository =
                new JooqBackupArtefactManifestRepository(new JooqTransactionBoundary(dsl));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> sampleRecord("check_point", Optional.empty()));
        assertTrue(refusal.getMessage().contains("backup_artefact_version_unresolvable"),
                "the refusal must name its reason");

        // The record could not even be constructed, so record() was never called and zero SQL statements ran.
        assertEquals(0, executedSql.size());
    }

    @Test
    void aCheckPointArtefactWithAResolvedSoftwareVersionRecordsNormally() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(1, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqBackupArtefactManifestRepository repository =
                new JooqBackupArtefactManifestRepository(new JooqTransactionBoundary(dsl));

        repository.record(sampleRecord("check_point", Optional.of("R81.20")), "system:worker",
                "backup_artefact_recorded");

        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("insert into backup_artefact")));
    }

    @Test
    void aPaloAltoArtefactMayCarryNoSoftwareVersionAtAll() {
        // palo_alto stores NULL -- constructing the record at all is the proof (no exception).
        BackupArtefactManifestRecord record = sampleRecord("palo_alto", Optional.empty());
        assertEquals(Optional.empty(), record.softwareVersion());
    }

    /** The retention pruner keys on backup_type: a Gaia snapshot must reach the column as snapshot, never the default. */
    @Test
    void aSnapshotManifestWritesItsBackupTypeAndEveryOtherRecordWritesStandard() {
        List<Object[]> insertBindings = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            if (ctx.sql().contains("insert into backup_artefact(")) {
                assertTrue(ctx.sql().contains("backup_type"), "the insert names the backup_type column");
                insertBindings.add(ctx.bindings());
            }
            return new MockResult[] { new MockResult(1, null) };
        };
        JooqBackupArtefactManifestRepository repository = new JooqBackupArtefactManifestRepository(
                new JooqTransactionBoundary(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES)));
        BackupArtefactManifestRecord standard = sampleRecord("check_point", Optional.of("R81.20"));
        BackupArtefactManifestRecord snapshot = new BackupArtefactManifestRecord("artefact-2", "device-1", Optional.empty(),
                ArtefactClass.BACKUP, "check_point", Optional.of("R81.20"), "a".repeat(64), "plain-sha", 100L,
                "cipher-sha", 132L, "v1", new byte[] { 1, 2, 3 }, ArtefactValidation.reachedWithoutRestore("V1"),
                "standard", Optional.empty(), "/app/artefact-store", Optional.empty(),
                BackupArtefactManifestRecord.BACKUP_TYPE_SNAPSHOT);

        repository.record(standard, "system:worker", "backup_artefact_recorded");
        repository.record(snapshot, "system:worker", "backup_artefact_recorded");

        assertEquals("standard", standard.backupType(), "the 18-component constructor records standard");
        assertEquals(2, insertBindings.size());
        assertEquals("standard", insertBindings.get(0)[insertBindings.get(0).length - 1]);
        assertEquals("snapshot", insertBindings.get(1)[insertBindings.get(1).length - 1]);
    }

    @Test
    void aBackupTypeOutsideTheV30VocabularyOrASnapshotOfAnotherClassIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BackupArtefactManifestRecord("artefact-3", "device-1",
                Optional.empty(), ArtefactClass.BACKUP, "check_point", Optional.of("R81.20"), "a".repeat(64), "plain-sha",
                100L, "cipher-sha", 132L, "v1", new byte[] { 1 }, ArtefactValidation.reachedWithoutRestore("V1"),
                "standard", Optional.empty(), "/app/artefact-store", Optional.empty(), "weekly"));
        assertThrows(IllegalArgumentException.class, () -> new BackupArtefactManifestRecord("artefact-4", "device-1",
                Optional.empty(), ArtefactClass.CONFIGURATION, "check_point", Optional.of("R81.20"), "a".repeat(64),
                "plain-sha", 100L, "cipher-sha", 132L, "v1", new byte[] { 1 }, ArtefactValidation.reachedWithoutRestore("V1"),
                "standard", Optional.empty(), "/app/artefact-store", Optional.empty(),
                BackupArtefactManifestRecord.BACKUP_TYPE_SNAPSHOT));
    }
}
