package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactRetrievalRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogReadRepository;

/**
 * The read side of the system-log programme against the V160/V161 schema: every query runs on PostgreSQL, the cursor, run
 * and chunk rows come back as the Logs screen needs them, and the unmasked-view ledger row (the existing
 * {@code backup_artefact_retrieval}) accepts a system-log artefact id. Synthetic values only.
 */
class SystemLogReadRepositoryTest {
    private static final String HEX = "a".repeat(64);
    private static final String DEV = "dev-synthetic";

    @Test
    void theIndexRowsComeBackAsTheScreenNeedsThemAndNoQueryNamesAFile() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("system_log_read")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                audited(db, "insert into system_log_cursor(device_id, source_key, inode, offset_bytes, fast_rotating, "
                        + "retention_horizon_seconds, updated_job_id) values ('" + DEV + "', 'cp_messages', '12345', 4096, true, 7200, 'job-1')");
                audited(db, "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                        + "reads_issued, duration_ms) values ('r1', '" + DEV + "', 'job-1', 'cp_messages', 'BACKLOG', 9000, 4096, 2, 100)");
                audited(db, "insert into system_log_run(run_id, device_id, job_id, source_key, outcome) values "
                        + "('r2', '" + DEV + "', 'job-1', 'cp_fwk_elg', 'NO_NEW_DATA')");
                audited(db, "insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, inode, "
                        + "start_offset, end_offset, bytes, lines, first_line_time, last_line_time, redaction_count, flags, "
                        + "artefact_ref, wrapped_data_key, plaintext_hmac, ciphertext_bytes) values ('c1', '" + DEV
                        + "', 'cp_messages', 'job-1', date '2026-10-17', '" + HEX + "', '12345', 0, 4096, 4096, 40, "
                        + "timestamp '2026-10-17 09:00:00', timestamp '2026-10-17 09:00:30', 2, array['LINE_SPLIT'], "
                        + "'artefact-1', '\\x0102'::bytea, '" + HEX + "', 900)");
                audited(db, "insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, known_lost_bytes, pending_bytes, "
                        + "extent_unknown) values ('g1', '" + DEV + "', 'cp_messages', 'job-1', 'BACKLOG', 0, 5000, false)");
                audited(db, "insert into device_crash_entry(entry_id, device_id, directory, name_hmac, process, size, mtime, baseline, "
                        + "first_seen_job, last_seen_job) values ('e1', '" + DEV + "', 'dump_usermode', '" + HEX
                        + "', 'fwd', 5000, 1790000000, false, 'job-1', 'job-1')");
                audited(db, "insert into device_crash_alert(alert_id, device_id, process, file_count, newest_mtime, job_id) values "
                        + "('a1', '" + DEV + "', 'fwd', 1, 1790000000, 'job-1')");
                audited(db, "insert into system_log_file(file_id, device_id, kind, job_id, remote_name_hmac, name_time, size, mtime, "
                        + "status, artefact_ref, wrapped_data_key, content_hmac, ciphertext_bytes, expires_at) values ('f1', '" + DEV
                        + "', 'cp_hcp_report', 'job-1', '" + HEX + "', timestamp '2026-10-17 03:00:12', 4956000, 1790000000, 'STORED', "
                        + "'artefact-2', '\\x0102'::bytea, '" + HEX + "', 4900000, now() + interval '7 days')");
            }
            var transactions = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var repository = new JooqSystemLogReadRepository(transactions);

            var sources = repository.sources(DEV);
            assertEquals(List.of("cp_fwk_elg", "cp_messages"), sources.stream().map(s -> s.sourceKey()).toList());
            var messages = sources.get(1);
            assertTrue(messages.hasCursor());
            assertTrue(messages.fastRotating());
            assertEquals(7200L, messages.retentionHorizonSeconds());
            assertEquals(4096L, messages.offsetBytes());
            assertEquals("BACKLOG", messages.lastOutcome());
            assertEquals(5000L, messages.pendingBytes());
            assertEquals(1L, messages.liveChunks());
            assertFalse(sources.get(0).hasCursor());
            assertEquals("NO_NEW_DATA", sources.get(0).lastOutcome());

            var job = repository.lastJob(DEV).orElseThrow();
            assertEquals("job-1", job.jobId());
            assertEquals("2026-10-17 09:00:00", job.windowFrom());
            assertEquals("2026-10-17 09:00:30", job.windowTo());
            assertEquals(1, job.outcomes().get("BACKLOG"));
            assertTrue(repository.lastJob("nobody").isEmpty());

            var chunks = repository.chunks(DEV, Optional.of("cp_messages"), Optional.of("2026-10-17"), 10);
            assertEquals(1, chunks.size());
            assertEquals("BACKLOG", chunks.get(0).outcome());
            assertEquals(List.of("LINE_SPLIT"), chunks.get(0).flags());
            assertEquals(2, chunks.get(0).redactionCount());
            assertTrue(repository.chunks(DEV, Optional.of("pan_system"), Optional.empty(), 10).isEmpty());
            assertTrue(repository.chunks(DEV, Optional.empty(), Optional.of("2026-10-16"), 10).isEmpty());

            var ref = repository.findChunk("c1").orElseThrow();
            assertEquals("artefact-1", ref.artefactId());
            assertEquals(2, ref.wrappedDataKey().length);
            assertTrue(repository.findChunk("missing").isEmpty());

            assertEquals(1, repository.gaps(Optional.of(DEV), 10).size());
            assertEquals(1, repository.gaps(Optional.empty(), 10).size());
            assertTrue(repository.gaps(Optional.of("other"), 10).isEmpty());
            assertEquals("fwd", repository.crashEntries(Optional.of(DEV), 10).get(0).process());
            assertEquals(1, repository.crashAlerts(Optional.of(DEV), 10).get(0).fileCount());
            var hcp = repository.hcpFiles(DEV, 10);
            assertEquals(1, hcp.size());
            assertEquals("2026-10-17 03:00:12", hcp.get(0).nameTime());
            assertEquals("STORED", hcp.get(0).status());

            // The unmasked-view ledger row: the retrieval ledger accepts the artefact id of a system-log chunk.
            new JooqBackupArtefactRetrievalRepository(transactions).record("ret-1", "artefact-1",
                    "system_log_unmasked_view chunk=c1 offset=0 limit=65536", "api", "actor-fp", "system_log_read_unmasked");
            try (var db = fixture.migrateConnection()) {
                assertEquals("1", scalar(db, "select count(*) from backup_artefact_retrieval where artefact_id = 'artefact-1'"));
                assertTrue(Integer.parseInt(scalar(db, "select count(*) from audit_log where table_name = 'backup_artefact_retrieval' "
                        + "and action_id = 'system_log_read_unmasked'")) >= 1);
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
