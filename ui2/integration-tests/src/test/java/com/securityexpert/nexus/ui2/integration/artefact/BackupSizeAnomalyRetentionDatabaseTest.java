package com.securityexpert.nexus.ui2.integration.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupSizeAnomalyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/**
 * V180 / PO decision 2026-10-30 over real rows: a 1 GB -> 29 GB backup is a size anomaly; the last good backup is kept
 * past the 7 days while the anomaly is open; only the newest anomalous copy is kept; "accept new size" releases the
 * pin and the age rule applies again. Synthetic values only. Needs PostgreSQL (excluded from the default build).
 */
class BackupSizeAnomalyRetentionDatabaseTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    private static void insertBackup(Connection db, String id, String device, long plaintextBytes, int ageDays)
            throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) {
                statement.execute("INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, "
                        + "key_id, wrapped_data_key, validation, retention_tier, recovery_volume_path, created_at) VALUES ('"
                        + id + "', '" + device + "', 'backup', 'check_point', '" + "a".repeat(64)
                        + "', 'plain-sha', " + plaintextBytes + ", 'cipher-sha', " + plaintextBytes + ", 'v1', decode('00', 'hex'), "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', '" + id + ".enc', now() - interval '" + ageDays + " days')");
            }
            db.commit();
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static List<String> ids(List<JooqRetentionQueries.Candidate> candidates) {
        return candidates.stream().map(JooqRetentionQueries.Candidate::artefactId).toList();
    }

    @Test
    void theLastGoodIsPinnedOlderAnomaliesArePrunedAndAcceptanceReleases() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("backup_size_anomaly_retention")) {
            fixture.runFlyway();
            String dev;
            String small;
            try (Connection db = fixture.migrateConnection()) {
                String credential = Ui2Rows.insertCredentialReference(db);
                dev = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                small = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                insertBackup(db, "good-12", dev, GIB, 12);
                insertBackup(db, "good-11", dev, GIB, 11);
                insertBackup(db, "good-10", dev, GIB + 5, 10);
                insertBackup(db, "small-3", small, 10L << 20, 3);
                insertBackup(db, "small-2", small, 10L << 20, 2);
                insertBackup(db, "small-1", small, 10L << 20, 1);
                insertBackup(db, "small-0", small, 90L << 20, 0);   // 9x but under the 1 GiB floor
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var anomalies = new JooqBackupSizeAnomalyRepository(boundary);
            var queries = new JooqRetentionQueries(boundary);

            assertTrue(anomalies.inspect("small-0", "system:worker").isEmpty(), "the floor keeps small devices out");
            assertTrue(anomalies.inspect("good-10", "system:worker").isEmpty(), "two earlier backups are too little history");

            // Seven nightly bloated copies, each checked at ingest.
            try (Connection db = fixture.migrateConnection()) {
                for (int age = 9; age >= 3; age--) {
                    insertBackup(db, "bad-" + age, dev, 29 * GIB, age);
                }
            }
            for (int age = 9; age >= 3; age--) {
                var detected = anomalies.inspect("bad-" + age, "system:worker").orElseThrow();
                assertEquals("good-10", detected.lastGoodArtefactId(), "the anomalous copies never become history");
            }
            assertTrue(anomalies.inspect("bad-3", "system:worker").isEmpty(), "one record per artefact");
            var open = anomalies.openAnomalies();
            assertEquals(1, open.size());
            assertEquals("bad-3", open.get(0).artefactId());
            assertEquals(7, open.get(0).openCopies());
            assertEquals("growth", open.get(0).direction());
            assertFalse(open.get(0).summary().contains(dev), "sizes and ratio only");

            // Age rule (7 days): good-12/11 are offered, good-10 (the last good) is kept past 7 days. Older anomalous
            // copies are offered by the superseded read whatever their age.
            assertEquals(List.of("good-12", "good-11"), ids(queries.expiredStandardBackups(7)).stream()
                    .filter(id -> id.startsWith("good")).toList());
            assertFalse(ids(queries.expiredStandardBackups(7)).contains("good-10"));
            assertEquals(List.of("bad-9", "bad-8", "bad-7", "bad-6", "bad-5", "bad-4"), ids(queries.supersededSizeAnomalies()));
            for (String id : ids(queries.supersededSizeAnomalies())) {
                queries.recordRemoved("ledger-" + id, id, "standard", "system:worker", "backup_artefact_pruned");
            }
            assertEquals(List.of(), ids(queries.supersededSizeAnomalies()), "the newest anomalous copy is kept");
            assertFalse(ids(queries.expiredStandardBackups(0)).contains("bad-3"), "kept for investigation while open");
            assertFalse(ids(queries.expiredStandardBackups(0)).contains("good-10"));

            // Accept new size: the pin is released and the age rule resumes; nothing else changed it.
            assertEquals(7, anomalies.accept(dev, "admin-fingerprint", "backup_size_anomaly_accept", "logs kept locally"));
            assertTrue(anomalies.openAnomalies().isEmpty());
            assertTrue(ids(queries.expiredStandardBackups(7)).contains("good-10"));
            assertTrue(ids(queries.expiredStandardBackups(0)).contains("bad-3"));
            assertEquals(0, anomalies.accept(dev, "admin-fingerprint", "backup_size_anomaly_accept", "again"));

            // The resolution is audited with its actor; the free-text reason is redacted from the audit image.
            try (Connection db = fixture.migrateConnection(); var statement = db.createStatement()) {
                var rows = statement.executeQuery("select count(*) from audit_log where table_name = 'backup_deviation_record' "
                        + "and action_id = 'backup_size_anomaly_accept'");
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }

            // After acceptance the new size is the normal: history restarts at the accepted copies, so a further
            // 29 GB backup is not an anomaly (and the old 1 GB backups no longer count).
            try (Connection db = fixture.migrateConnection()) {
                insertBackup(db, "bad-new", dev, 29 * GIB, 0);
            }
            assertTrue(anomalies.inspect("bad-new", "system:worker").isEmpty());
        }
    }

    @Test
    void aShrinkToAFifthIsAnAnomalyToo() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("backup_size_anomaly_shrink")) {
            fixture.runFlyway();
            String dev;
            try (Connection db = fixture.migrateConnection()) {
                String credential = Ui2Rows.insertCredentialReference(db);
                dev = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                insertBackup(db, "g-3", dev, 10 * GIB, 3);
                insertBackup(db, "g-2", dev, 10 * GIB, 2);
                insertBackup(db, "g-1", dev, 10 * GIB, 1);
                insertBackup(db, "truncated", dev, GIB, 0);
            }
            var anomalies = new JooqBackupSizeAnomalyRepository(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var detected = anomalies.inspect("truncated", "system:worker").orElseThrow();
            assertEquals("g-1", detected.lastGoodArtefactId());
            assertEquals("shrink", anomalies.openAnomalies().get(0).direction());
        }
    }
}
