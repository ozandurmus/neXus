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
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/**
 * V164 / PO decision 2026-10-18: configuration archives are pruned after the policy's configuration days, by
 * {@code created_at}; a device's latest live configuration archive stays however old it is; the backup class is not
 * touched; a removal is a ledger row and the artefact row stays. Synthetic values only. Needs PostgreSQL (excluded from
 * the default build like the other integration tests).
 */
class ConfigurationArchiveRetentionDatabaseTest {

    private static void insertArtefact(Connection db, String id, String device, String artefactClass, int ageDays)
            throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) {
                statement.execute("INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, "
                        + "key_id, wrapped_data_key, validation, retention_tier, recovery_volume_path, created_at) VALUES ('"
                        + id + "', '" + device + "', '" + artefactClass + "', 'palo_alto', '" + "a".repeat(64)
                        + "', 'plain-sha', 10, 'cipher-sha', 26, 'v1', decode('00', 'hex'), "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', '" + id + ".enc', now() - interval '" + ageDays + " days')");
            }
            db.commit();
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Test
    void theCutoffIsTheConfigurationDaysAndTheDevicesLatestArchiveIsAlwaysKept() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("configuration_archive_retention")) {
            fixture.runFlyway();
            String devA;
            String devB;
            try (Connection db = fixture.migrateConnection()) {
                String credential = Ui2Rows.insertCredentialReference(db);
                devA = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                devB = Ui2Rows.insertDevice(db, credential, "ENROLLED");
                insertArtefact(db, "cfg-a-40", devA, "configuration", 40);
                insertArtefact(db, "cfg-a-35", devA, "configuration", 35);
                insertArtefact(db, "cfg-a-29", devA, "configuration", 29);
                insertArtefact(db, "cfg-b-90", devB, "configuration", 90);   // the only archive of B, and old
                insertArtefact(db, "bak-a-90", devA, "backup", 90);          // backup class: not this read
            }
            var queries = new JooqRetentionQueries(new JooqTransactionBoundary(
                    DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));

            List<String> expired = queries.expiredConfigurationArchives(30).stream()
                    .map(JooqRetentionQueries.Candidate::artefactId).toList();
            assertEquals(List.of("cfg-b-90", "cfg-a-40", "cfg-a-35"), expired, "older than 30 days, configuration only");

            assertTrue(queries.isLatestLiveConfigurationArchive("cfg-b-90"), "the sole archive is the latest");
            assertTrue(queries.isLatestLiveConfigurationArchive("cfg-a-29"));
            assertFalse(queries.isLatestLiveConfigurationArchive("cfg-a-40"));
            assertFalse(queries.isLatestLiveConfigurationArchive("cfg-a-35"));

            // A removed archive leaves the candidate list; the row stays, and the ledger carries the removal.
            queries.recordRemoved("ledger-cfg-a-40", "cfg-a-40", "standard", "system:worker", "backup_artefact_pruned");
            assertEquals(List.of("cfg-b-90", "cfg-a-35"), queries.expiredConfigurationArchives(30).stream()
                    .map(JooqRetentionQueries.Candidate::artefactId).toList());
            try (Connection db = fixture.migrateConnection(); var statement = db.createStatement()) {
                var rows = statement.executeQuery("select count(*) from backup_artefact where artefact_id = 'cfg-a-40'");
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                rows = statement.executeQuery("select count(*) from artefact_retention_ledger where artefact_id = 'cfg-a-40' and event = 'removed'");
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }

            // With the older one gone, the 35-day archive is still not the latest (the 29-day one is newer).
            assertFalse(queries.isLatestLiveConfigurationArchive("cfg-a-35"));
        }
    }
}
