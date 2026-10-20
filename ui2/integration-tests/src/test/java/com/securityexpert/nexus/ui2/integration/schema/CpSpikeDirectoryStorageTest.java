package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.FileRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.SpikeDirectory;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.SpikeDirectoryState;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.SpikeWrite;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.JooqSystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Gap;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Run;

/**
 * V167 (Spike Detective data, phase B contract §8.3 as amended by the PO on 2026-10-20): the data-directory state table
 * with its constraints, the fenced baseline listing and per-directory writes (archive manifest, file row, directory row in
 * one transaction), duplicate refusal by name HMAC, the read side (baseline counted, not listed), the app grant and the
 * absence of an audit trigger (collected listing state, NOT_MUTATION_BEARING). Synthetic values only.
 */
class CpSpikeDirectoryStorageTest {
    private static final String HEX = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);
    private static final String HEX_C = "c".repeat(64);
    private static final String CLASS = "X_spike_X_NNN_NNN-NN-NN_NN-NN-NN";

    private static BackupArtefactManifestRecord manifest(String id, String device) {
        return new BackupArtefactManifestRecord(id, device, Optional.empty(), ArtefactClass.SYSTEM_LOG, "check_point",
                Optional.of("unknown"), HEX, HEX, 120, HEX, 90, "key-1", new byte[] {1, 2, 3},
                ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(Instant.now().plusSeconds(30 * 86400)), "check_point/" + device + "/" + id + ".enc", Optional.empty());
    }

    @Test
    void baselineAndDirectoriesAreFencedKeyedByNameHmacAndReadBackWithoutNames() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cp_spike_directory_storage")) {
            fixture.runFlyway();
            String device, job;
            try (var db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                job = CollectionEngineRows.insertJobWithCapability(db, device, "cp_system_log_collect");
                audited(db, "update jobs set state='CLAIMED', lease_worker_id='general-synthetic', lease_epoch=1, "
                        + "lease_expires_at=now()+interval '10 minutes' where job_id='" + job + "'");
                audited(db, "update jobs set state='EXECUTING' where job_id='" + job + "'");
                assertEquals("0", scalar(db, "select count(*) from pg_trigger t join pg_class c on c.oid = t.tgrelid "
                        + "where c.relname = 'system_log_spike_directory' and not t.tgisinternal"), "not audited by a trigger");
                assertEquals("t", scalar(db, "select has_table_privilege('ui2_app', 'system_log_spike_directory', 'INSERT')::text"));
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var repository = new JooqSystemLogCollectionRepository(boundary);
            var baseline = List.of(SpikeDirectory.listed("d1", HEX, CLASS, 1_760_000_000L, SpikeDirectoryState.BASELINE),
                    SpikeDirectory.listed("d2", HEX_B, CLASS, 1_760_000_100L, SpikeDirectoryState.BASELINE));

            assertFalse(repository.spikeBaselineTaken(device));
            assertFalse(repository.recordSpikeListing(job, 2, device, new Run("NO_NEW_DATA", 0, 0, 1, 1, "{}"), baseline, List.of()),
                    "a stale lease writes nothing");
            assertTrue(repository.recordSpikeListing(job, 1, device, new Run("NO_NEW_DATA", 0, 0, 1, 1, "{\"baseline\":true}"),
                    baseline, List.of()));
            assertTrue(repository.spikeBaselineTaken(device));
            assertEquals(Set.of(HEX, HEX_B), repository.spikeDirectoryHmacs(device));

            String members = "{\"file:perf_X_NNN.log\":{\"count\":1,\"bytes\":10851,\"fetched\":1}}";
            var file = new FileRow("f1", "cp_spike_data", HEX_C, null, 10_851, 1_760_000_200L, "STORED", List.of(), HEX,
                    manifest("artefact-f1", device), Instant.now().plusSeconds(30 * 86400));
            var collected = new SpikeDirectory("d3", HEX_C, CLASS, 1_760_000_200L, SpikeDirectoryState.COLLECTED, 2, 1, 10_937,
                    10_851, members, "f1");
            assertEquals(SpikeWrite.NOT_RECORDED, repository.recordSpikeDirectory(job, 2, device, collected, Optional.of(file)));
            assertEquals(SpikeWrite.STORED, repository.recordSpikeDirectory(job, 1, device, collected, Optional.of(file)));
            assertEquals(SpikeWrite.DUPLICATE, repository.recordSpikeDirectory(job, 1, device,
                    new SpikeDirectory("d4", HEX_C, CLASS, 1, SpikeDirectoryState.TOO_LARGE, 0, 0, 0, 0, "{}", null), Optional.empty()),
                    "one row per (device, name HMAC)");
            assertFalse(repository.recordSpikeListing(job, 1, device, new Run("COLLECTED", 10_851, 10_851, 3, 1, "{}"),
                    List.of(SpikeDirectory.listed("d1-again", HEX, CLASS, 1, SpikeDirectoryState.NAME_REJECTED)),
                    List.of(new Gap("BACKLOG", 0, 0, false, null, 1_760_000_300L, "{\"pending_directories\":2}"))),
                    "one run row per job and source");

            var read = new JooqSystemLogReadRepository(boundary);
            var rows = read.spikeDirectories(device, 10);
            assertEquals(1, rows.size(), "baseline rows are counted, not listed");
            assertEquals("COLLECTED", rows.get(0).state());
            assertEquals(CLASS, rows.get(0).nameClass());
            assertEquals(10_851, rows.get(0).fetchedBytes());
            assertNotNull(rows.get(0).expiresAt());
            assertEquals(Map.of("BASELINE", 2L, "COLLECTED", 1L), read.spikeDirectoryCounts(device));

            try (var db = fixture.migrateConnection()) {
                assertEquals("f1", scalar(db, "select file_id from system_log_spike_directory where directory_id = 'd3'"));
                assertEquals("1", scalar(db, "select count(*) from backup_artefact where artefact_id = 'artefact-f1'"));
                assertEquals("1", scalar(db, "select count(*) from system_log_file where kind = 'cp_spike_data'"));
                assertEquals(HEX_C, scalar(db, "select remote_name_hmac from system_log_file where file_id = 'f1'"));
                // Constraints: names only as HMACs and classes; no name keys in the member summary; a file only when collected.
                String insert = "insert into system_log_spike_directory(directory_id, device_id, name_hmac, name_class, mtime, "
                        + "state, members, file_id, job_id) values ";
                refused(db, insert + "('x1', 'd', 'not-a-digest', 'X', 1, 'BASELINE', '{}', null, 'j')");
                refused(db, insert + "('x2', 'd', '" + HEX + "', 'a name with spaces', 1, 'BASELINE', '{}', null, 'j')");
                refused(db, insert + "('x3', 'd', '" + HEX + "', 'X', 1, 'FETCHED', '{}', null, 'j')");
                refused(db, insert + "('x4', 'd', '" + HEX + "', 'X', 1, 'COLLECTED', '{\"name\":\"x\"}', null, 'j')");
                refused(db, insert + "('x5', 'd', '" + HEX + "', 'X', 1, 'TOO_LARGE', '{}', 'f1', 'j')");
                refused(db, insert + "('x6', '" + device + "', '" + HEX + "', 'X', 1, 'BASELINE', '{}', null, 'j')");
                audited(db, insert + "('x7', 'd', '" + HEX + "', 'X', 1, 'BASELINE', '{}', null, 'j')");
            }
        }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void refused(Connection db, String sql) {
        assertThrows(SQLException.class, () -> audited(db, sql), sql);
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
