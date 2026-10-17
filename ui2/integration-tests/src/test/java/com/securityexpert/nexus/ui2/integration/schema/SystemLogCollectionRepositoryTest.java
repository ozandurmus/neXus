package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.CollectionEngineRows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.*;

/**
 * System-log phase B storage (V160, DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 4.1/5.2): one fenced, audited
 * transaction writes the run row, the system_log artefact manifest, the chunk row, the cursor and the gap rows together;
 * a stale lease or a second write for the same (job, source) writes nothing. Synthetic values only.
 */
class SystemLogCollectionRepositoryTest {
    private static final String HEX = "a".repeat(64);

    @Test
    void aSourceWriteIsAtomicFencedToTheLeaseAndMovesTheCursorWithTheChunk() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("system_log_collection")) {
            fixture.runFlyway();
            String device, job;
            try (var db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                job = CollectionEngineRows.insertJobWithCapability(db, device, "pan_system_log_collect");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + job + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + job + "'");
            }
            var repository = new JooqSystemLogCollectionRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertEquals(Settings.DEFAULTS, repository.settings());
            assertTrue(repository.findPanCursor(device, "pan_system").isEmpty());

            var manifest = new BackupArtefactManifestRecord("artefact-1", device, Optional.empty(), ArtefactClass.SYSTEM_LOG,
                    "palo_alto", Optional.empty(), HEX, HEX, 120, HEX, 90, "key-1", new byte[] {1, 2, 3},
                    ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                    Optional.of(Instant.now().plusSeconds(86400)), "palo_alto/" + device + "/x.enc", Optional.empty());
            var chunk = new Chunk("chunk-1", LocalDate.of(2026, 10, 17), HEX, 0, 120, 120, 4, "2026-10-15 10:00:00",
                    "2026-10-15 10:00:02", 1, List.of(), manifest);
            var write = new SourceWrite(new Run("GAP", 500, 120, 2, 4000, "{\"seed\":true,\"pages\":[]}"), Optional.of(chunk),
                    Optional.of(new PanCursor("2026/10/15 10:00:02", List.of("103", "104"))),
                    List.of(new Gap("INITIAL_BASELINE", 0, 0, true, null, null, "{\"seed_entries\":2000}")));

            assertFalse(repository.record(job, 2, device, "pan_system", write), "a stale epoch writes nothing");
            try (var db = fixture.migrateConnection()) {
                assertEquals("0", scalar(db, "select count(*) from system_log_run"));
                assertEquals("0", scalar(db, "select count(*) from backup_artefact where artefact_class = 'system_log'"));
            }
            assertTrue(repository.record(job, 1, device, "pan_system", write));
            assertFalse(repository.record(job, 1, device, "pan_system", write), "one run row per job and source");

            var cursor = repository.findPanCursor(device, "pan_system").orElseThrow();
            assertEquals("2026/10/15 10:00:02", cursor.lastReceiveTime());
            assertEquals(List.of("103", "104"), cursor.boundarySeqnos().stream().sorted().toList());
            try (var db = fixture.migrateConnection()) {
                assertEquals("1", scalar(db, "select count(*) from system_log_run where outcome = 'GAP' and job_id = '" + job + "'"));
                assertEquals("1", scalar(db, "select count(*) from backup_artefact where artefact_id = 'artefact-1' and artefact_class = 'system_log'"));
                assertEquals("1", scalar(db, "select count(*) from artefact_retention_ledger where artefact_id = 'artefact-1' and event = 'created'"));
                assertEquals("1", scalar(db, "select count(*) from system_log_chunk where artefact_ref = 'artefact-1' and inode is null"));
                assertEquals("1", scalar(db, "select count(*) from system_log_gap where kind = 'INITIAL_BASELINE' and extent_unknown"));
                assertEquals("1", scalar(db, "select count(*) from system_log_cursor where updated_job_id = '" + job + "' and offset_bytes = 0"));
                assertTrue(Integer.parseInt(scalar(db, "select count(*) from audit_log where table_name in "
                        + "('system_log_run','system_log_chunk','system_log_cursor','system_log_gap')")) >= 4);
            }

            // A later run moves the cursor; a no-data run writes only its run row and leaves the cursor alone.
            String second;
            try (var db = fixture.migrateConnection()) {
                second = CollectionEngineRows.insertJobWithCapability(db, device, "pan_system_log_collect");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + second + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + second + "'");
            }
            assertTrue(repository.record(second, 1, device, "pan_system", new SourceWrite(
                    new Run("NO_NEW_DATA", 10, 0, 2, 100, "{}"), Optional.empty(), Optional.empty(), List.of())));
            assertEquals("2026/10/15 10:00:02", repository.findPanCursor(device, "pan_system").orElseThrow().lastReceiveTime());

            // The schema still refuses what it should: a log body in the detail column.
            String third;
            try (var db = fixture.migrateConnection()) {
                third = CollectionEngineRows.insertJobWithCapability(db, device, "pan_system_log_collect");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + third + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + third + "'");
            }
            assertThrows(RuntimeException.class, () -> repository.record(third, 1, device, "pan_config", new SourceWrite(
                    new Run("UNKNOWN", 0, 0, 1, 1, "{\"lines\":[\"synthetic-raw-marker\"]}"), Optional.empty(), Optional.empty(), List.of())));
            try (var db = fixture.migrateConnection()) {
                assertEquals("0", scalar(db, "select count(*) from system_log_run where job_id = '" + third + "'"));
            }
        }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void audited(Connection db, String sql) throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) { statement.execute(sql); }
            db.commit();
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }
}
