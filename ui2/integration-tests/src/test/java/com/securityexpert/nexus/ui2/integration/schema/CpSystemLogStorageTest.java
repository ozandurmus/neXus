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
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.*;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogRetentionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.*;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogRetentionRepository.Candidate;

/**
 * Check Point system-log storage (V160 + V161; phase B contract sections 4.1, 5.2, 5.3, 7, 8): a chunk and the cursor
 * it advances in one fenced transaction, the V161 chunk identity (true duplicate refused, reused inode allowed), the
 * crash listing, whole-file rows, the ledgered prune, and the backup vault accounting without system-log artefacts.
 * Synthetic values only.
 */
class CpSystemLogStorageTest {
    private static final String HEX = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);

    private static BackupArtefactManifestRecord manifest(String id, String device, String sha) {
        return new BackupArtefactManifestRecord(id, device, Optional.empty(), ArtefactClass.SYSTEM_LOG, "check_point",
                Optional.of("unknown"), HEX, sha, 120, HEX, 90, "key-1", new byte[] {1, 2, 3},
                ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(Instant.now().plusSeconds(86400)), "check_point/" + device + "/" + id + ".enc", Optional.empty());
    }

    private static CpChunk chunk(String id, String device, long start, String sha) {
        return new CpChunk(new Chunk(id, LocalDate.of(2026, 10, 17), HEX, start, start + 120, 120, 3, "2026-10-17 10:00:00",
                "2026-10-17 10:00:02", 1, List.of("ROTATION_FOLLOWED"), sha, manifest("artefact-" + id, device, HEX)), "77", "cp-text-v1");
    }

    @Test
    void chunksCursorsListingsFilesAndPrunesAreFencedAuditedAndIdentityAware() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cp_system_log_storage")) {
            fixture.runFlyway();
            String device, job;
            try (var db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                job = CollectionEngineRows.insertJobWithCapability(db, device, "cp_system_log_collect");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + job + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + job + "'");
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var repository = new JooqSystemLogCollectionRepository(boundary);
            var cursor = new CpCursor("77", 120, HEX, List.of(new SnapshotEntry("messages", "77", 130, 1_760_000_000L),
                    new SnapshotEntry("messages.1", "76", 900, 1_759_000_000L)), 1_760_000_100L);
            var gap = new Gap("INITIAL_BASELINE", 500, 0, false, 1_760_000_100L, 1_760_000_100L, "{\"file_digests\":[\"" + HEX + "\"]}");

            assertEquals(ChunkWrite.NOT_RECORDED, repository.recordChunk(job, 2, device, "cp_messages", chunk("c1", device, 0, HEX), cursor, List.of(gap)));
            assertEquals(ChunkWrite.STORED, repository.recordChunk(job, 1, device, "cp_messages", chunk("c1", device, 0, HEX), cursor, List.of(gap)));
            assertEquals(ChunkWrite.DUPLICATE, repository.recordChunk(job, 1, device, "cp_messages", chunk("c2", device, 0, HEX), cursor, List.of()),
                    "the same bytes at the same offset of the same file are a duplicate");
            assertEquals(ChunkWrite.STORED, repository.recordChunk(job, 1, device, "cp_messages", chunk("c3", device, 0, HEX_B), cursor, List.of()),
                    "V161: a reused inode with other content (another content HMAC) is a different chunk");
            var read = repository.findCpCursor(device, "cp_messages").orElseThrow();
            assertEquals(cursor, read);
            assertTrue(repository.recordCpSource(job, 1, device, "cp_messages", new Run("GAP", 240, 240, 2, 10, "{\"reads\":2}"),
                    Optional.empty(), List.of()));
            assertFalse(repository.recordCpSource(job, 1, device, "cp_messages", new Run("GAP", 0, 0, 0, 0, "{}"),
                    Optional.empty(), List.of()), "one run row per job and source");
            assertEquals(Optional.of("GAP"), repository.lastOutcome(device, "cp_messages"));

            assertFalse(repository.crashBaselineTaken(device));
            assertTrue(repository.recordCrashListing(job, 1, device, new Run("COLLECTED", 0, 0, 1, 1, "{\"baseline\":true}"),
                    new CrashListingWrite(List.of(new NewCrashEntry("e1", "dump_usermode", HEX, "fwk", 10, 1_760_000_000L, true)),
                            List.of(), List.of(), List.of(new CrashAlert("a1", "UNKNOWN", 1, 1_760_000_000L)))));
            assertTrue(repository.crashBaselineTaken(device));
            assertEquals(1, repository.liveCrashEntries(device).size());

            var file = new FileRow("f1", "cp_hcp_report", HEX, "2026-10-17 03:00:00", 4000, 1_760_000_000L, "STORED",
                    List.of(), HEX, manifest("artefact-f1", device, HEX), Instant.now().plusSeconds(7 * 86400));
            assertTrue(repository.recordFile(job, 1, device, "cp_hcp_report", new Run("COLLECTED", 4000, 4000, 2, 5, "{}"),
                    Optional.of(file), List.of()));
            assertTrue(repository.fileStored(device, "cp_hcp_report", HEX, 1_760_000_000L));

            try (var db = fixture.migrateConnection()) {
                assertEquals("cp-text-v1", scalar(db, "select redaction_rule_set from system_log_chunk where chunk_id = 'c1'"));
                assertEquals(HEX, scalar(db, "select plaintext_hmac from system_log_chunk where chunk_id = 'c1'"));
                assertEquals(HEX, scalar(db, "select content_hmac from system_log_file where file_id = 'f1'"));
                assertEquals("0", scalar(db, "select count(*) from information_schema.columns where table_name in "
                        + "('system_log_chunk', 'system_log_file') and column_name in ('plaintext_sha256', 'sha256')"),
                        "no bare content digest in the system-log tables (contract section 12)");
                assertEquals("2", scalar(db, "select count(*) from system_log_chunk where device_id = '" + device + "'"));
                assertEquals("0", scalar(db, "select count(*) from backup_artefact where artefact_id = 'artefact-c2'"),
                        "a duplicate leaves no manifest");
                assertEquals("1", scalar(db, "select count(*) from system_log_gap where kind = 'INITIAL_BASELINE'"));
                assertEquals("1", scalar(db, "select count(*) from device_crash_alert where job_id = '" + job + "'"));
            }

            // Backup vault accounting ignores system-log artefacts; the prune is ledgered and keeps the index row.
            assertEquals(0, new JooqRetentionQueries(boundary).liveCiphertextBytes());
            var retention = new JooqSystemLogRetentionRepository(boundary);
            assertEquals(List.of(device), retention.devicesOverCap(100).stream().map(u -> u.deviceId()).toList());
            List<Candidate> oldest = retention.oldestCapped(device, 10);
            assertEquals(2, oldest.size());
            assertTrue(retention.markPruned(oldest.get(0), "RETENTION_SHORTENED_BY_CAP"));
            assertFalse(retention.markPruned(oldest.get(0), "RETENTION_SHORTENED_BY_CAP"));
            retention.recordCapGap("system-log-retention:2026-10-17", device, "cp_messages", 120, "{\"artefacts\":1}");
            try (var db = fixture.migrateConnection()) {
                assertEquals("1", scalar(db, "select count(*) from system_log_chunk where prune_reason = 'RETENTION_SHORTENED_BY_CAP'"));
                assertEquals("1", scalar(db, "select count(*) from artefact_retention_ledger where event = 'removed'"));
                assertEquals("1", scalar(db, "select count(*) from system_log_gap where kind = 'RETENTION_SHORTENED_BY_CAP'"));
            }
            assertTrue(retention.expiredChunks(30, 10).isEmpty());
            assertEquals(0, retention.pruneLedgerRows(180));
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
