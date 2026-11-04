package com.securityexpert.nexus.ui2.integration.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.EntryType;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactEntryRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupSizeAnomalyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/**
 * V189 / PO request 2026-11-04 over real rows: a backup that is large against its vendor's fleet raises one
 * {@code fleet_large} row per artefact, a stored content listing with Check Point firewall logs raises one
 * {@code firewall_logs} row per artefact, neither touches retention, the notification route exists and is enabled.
 * Synthetic values only. Needs PostgreSQL (excluded from the default build).
 */
class BackupSizeAlertDatabaseTest {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

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

    private static long count(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void theNotificationRouteIsSeededEnabledAndTheDeviationClassesAreAccepted() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("backup_size_alert_schema")) {
            fixture.runFlyway();
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1, count(db, "select count(*) from notification_routes where type = 'backup_size_anomaly' "
                        + "and enabled = true and recipients is null"));
                assertEquals(10, count(db, "select count(*) from notification_routes"));
            }
        }
    }

    @Test
    void aLargeBackupAlertsOncePerArtefactAndRetentionIsUnchanged() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("backup_size_alert_fleet")) {
            fixture.runFlyway();
            String big;
            try (Connection db = fixture.migrateConnection()) {
                String credential = Ui2Rows.insertCredentialReference(db);
                big = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                insertBackup(db, "big-3", big, 28 * GIB, 3);   // always large: no own history, so no size anomaly
                insertBackup(db, "big-2", big, 28 * GIB, 2);
                for (int i = 0; i < 4; i++) {
                    String other = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                    insertBackup(db, "norm-" + i, other, (200 + 10L * i) * MIB, 1);
                }
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var alerts = new JooqBackupSizeAnomalyRepository(boundary);
            var queries = new JooqRetentionQueries(boundary);
            List<String> before = queries.expiredStandardBackups(0).stream().map(JooqRetentionQueries.Candidate::artefactId).toList();

            assertTrue(alerts.inspectFleet("norm-0", "system:worker").isEmpty(), "a normal backup is not alerted");
            var detected = alerts.inspectFleet("big-2", "system:worker").orElseThrow();
            assertTrue(detected.verdict().ratio() >= 10);
            assertTrue(alerts.inspectFleet("big-2", "system:worker").isEmpty(), "once per artefact");
            assertTrue(alerts.inspectFleet("big-3", "system:worker").isPresent(), "each artefact has its own alert");

            try (Connection db = fixture.migrateConnection()) {
                assertEquals(2, count(db, "select count(*) from backup_deviation_record where deviation_class = 'fleet_large' "
                        + "and previous_artefact_id is null and resolved_at is null"));
                assertEquals(0, count(db, "select count(*) from backup_deviation_record where summary like '%FW-%'"));
            }
            assertTrue(alerts.openAnomalies().isEmpty(), "a fleet alert is not an own-history anomaly");
            assertEquals(before, queries.expiredStandardBackups(0).stream().map(JooqRetentionQueries.Candidate::artefactId).toList(),
                    "retention is unchanged by the alert rows");
            assertTrue(queries.supersededSizeAnomalies().isEmpty());
        }
    }

    @Test
    void aListingWithFirewallLogsAlertsOncePerArtefactAndNeverNamesAFile() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("backup_size_alert_logs")) {
            fixture.runFlyway();
            String dev;
            try (Connection db = fixture.migrateConnection()) {
                dev = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                insertBackup(db, "logs-1", dev, 600 * GIB, 0);
                insertBackup(db, "clean-1", dev, GIB, 1);
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var entries = new JooqBackupArtefactEntryRepository(boundary);
            var queries = new JooqRetentionQueries(boundary);
            List<String> before = queries.expiredStandardBackups(0).stream().map(JooqRetentionQueries.Candidate::artefactId).toList();

            entries.recordListed("clean-1", List.of(
                    new Entry("opt/CPsuite-R81.20/fw1/log/fw.log", EntryType.FILE, 99 * MIB, java.util.Optional.empty()),
                    new Entry("etc/passwd", EntryType.FILE, GIB, java.util.Optional.empty())));
            entries.recordListed("logs-1", List.of(
                    new Entry("opt/CPsuite-R81.20/fw1/log/2026-11-03_000000.log", EntryType.FILE, 270 * GIB, java.util.Optional.empty()),
                    new Entry("opt/CPsuite-R81.20/fw1/conf/objects_5_0.C", EntryType.FILE, 330 * GIB, java.util.Optional.empty()),
                    new Entry("opt/CPsuite-R81.20/fw1/log", EntryType.DIR, 0, java.util.Optional.empty())));
            entries.recordListed("logs-1", List.of(
                    new Entry("opt/CPsuite-R81.20/fw1/log/2026-11-03_000000.log", EntryType.FILE, 270 * GIB, java.util.Optional.empty())));

            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1, count(db, "select count(*) from backup_deviation_record where deviation_class = 'firewall_logs'"),
                        "one alert for logs-1, none for clean-1, and a re-list does not alert again");
                assertEquals(1, count(db, "select count(*) from backup_deviation_record where artefact_id = 'logs-1' "
                        + "and deviation_class = 'firewall_logs' and summary = 'Backup contains 270.0 GiB of firewall logs "
                        + "(45.0% of the archive contents)'"));
                assertEquals(0, count(db, "select count(*) from backup_deviation_record where summary like '%CPsuite%' "
                        + "or details::text like '%CPsuite%'"));
                assertEquals(2, count(db, "select count(*) from backup_artefact_content_listing where state = 'LISTED'"));
            }
            assertEquals(before, queries.expiredStandardBackups(0).stream().map(JooqRetentionQueries.Candidate::artefactId).toList(),
                    "retention is unchanged by the alert rows");
        }
    }
}
