package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.JooqAsaConfigRelationRepository;
import com.securityexpert.nexus.ui2.service.policy.PolicyInstallMonitoringService;

/** V250/V253 against a real PostgreSQL 16 server: one row per backup, re-keyed to the manifest id, joined by the service query. */
class AsaConfigRelationTest {

    private static Ui2PostgresFixture fixture;
    private static JooqAsaConfigRelationRepository repository;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("asa_config_relation");
        repository = new JooqAsaConfigRelationRepository(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    private static void insertBackup(Connection db, String id, String device, String storageRef, String createdAt) throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) {
                statement.execute("INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, "
                        + "key_id, wrapped_data_key, validation, retention_tier, recovery_volume_path, created_at) VALUES ('"
                        + id + "', '" + device + "', 'backup', 'cisco_asa', '" + "a".repeat(64)
                        + "', 'plain-sha', 10, 'cipher-sha', 10, 'v1', decode('00', 'hex'), "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', '" + storageRef + "', '" + createdAt + "'::timestamptz)");
            }
            db.commit();
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static String serviceRelation(Connection db, String device) throws SQLException {
        try (var st = db.prepareStatement("select b.relation from devices d left join lateral ("
                + PolicyInstallMonitoringService.ASA_LATEST_BACKUP_RELATION_SQL + ") b on true where d.device_id = ?")) {
            st.setString(1, device);
            try (ResultSet rs = st.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    @Test
    void recordsOneRowPerKeyAndReplacesItWithKeywordCounts() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String device = Ui2Rows.insertDevice(app, Ui2Rows.insertCredentialReference(app), "ENROLLED");
            repository.record("artefact-1", device, "MISMATCH", 40, 3, 2, Map.of("crypto", 3));
            repository.record("artefact-1", device, "MATCH", 40, 0, 2, Map.of());
            try (var st = app.prepareStatement("select relation, lines_compared, lines_differing, lines_equal_by_mask, "
                    + "differing_keywords::text, count(*) over () from asa_config_relation where artefact_id = 'artefact-1'");
                    ResultSet rs = st.executeQuery()) {
                rs.next();
                assertEquals("MATCH", rs.getString(1));
                assertEquals(40, rs.getInt(2));
                assertEquals(0, rs.getInt(3));
                assertEquals(2, rs.getInt(4));
                assertEquals("{}", rs.getString(5));
                assertEquals(1, rs.getInt(6));
            }
            repository.record("artefact-2", device, "MISMATCH", 40, 3, 0, Map.of("crypto", 2, "object", 1));
            try (var st = app.prepareStatement("select differing_keywords->>'crypto', differing_keywords->>'object' "
                    + "from asa_config_relation where artefact_id = 'artefact-2'"); ResultSet rs = st.executeQuery()) {
                rs.next();
                assertEquals("2", rs.getString(1));
                assertEquals("1", rs.getString(2));
            }
        }
    }

    @Test
    void theServiceJoinFindsTheRelationOnceTheRowIsLinkedToTheManifestId() throws SQLException {
        try (Connection db = fixture.migrateConnection()) {
            String device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            insertBackup(db, "manifest-id-1", device, "storage-ref-1", "2026-11-18T22:00:00Z");
            // the worker records under the storage reference while the bundle is written: no join yet
            repository.record("storage-ref-1", device, "MISMATCH", 10, 1, 0, Map.of("snmp-server", 1));
            assertNull(serviceRelation(db, device));
            // after the manifest row: re-keyed to the id the service joins on
            assertTrue(repository.linkManifest("storage-ref-1", "manifest-id-1"));
            assertEquals("MISMATCH", serviceRelation(db, device));
            assertFalse(repository.linkManifest("storage-ref-1", "manifest-id-1"), "second link finds nothing to re-key");
            // a newer backup without a relation reads as unmeasured, not as the older backup's verdict
            insertBackup(db, "manifest-id-2", device, "storage-ref-2", "2026-11-19T22:00:00Z");
            assertNull(serviceRelation(db, device));
        }
    }

    @Test
    void theReaderReturnsTheNewestBackupsKeywordCountsAndSkipsAnUnmeasuredNewestBackup() throws SQLException {
        var reader = new com.securityexpert.nexus.ui2.persistence.device.AsaConfigRelationReader(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
        try (Connection db = fixture.migrateConnection()) {
            String device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            String other = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            insertBackup(db, "rd-manifest-1", device, "rd-ref-1", "2026-11-18T22:00:00Z");
            repository.record("rd-ref-1", device, "MISMATCH", 20, 4, 0, Map.of("access-list", 3, "object-group", 1));
            assertTrue(repository.linkManifest("rd-ref-1", "rd-manifest-1"));
            insertBackup(db, "rd-manifest-2", other, "rd-ref-2", "2026-11-18T22:00:00Z");
            repository.record("rd-ref-2", other, "MATCH", 20, 0, 0, Map.of());
            assertTrue(repository.linkManifest("rd-ref-2", "rd-manifest-2"));

            var rows = reader.latestPerDevice().stream().filter(r -> r.deviceId().equals(device) || r.deviceId().equals(other)).toList();
            assertEquals(2, rows.size());
            var mismatch = rows.stream().filter(r -> r.deviceId().equals(device)).findFirst().orElseThrow();
            assertEquals("MISMATCH", mismatch.relation());
            assertEquals(Map.of("access-list", 3, "object-group", 1), mismatch.differingKeywords());
            assertEquals(java.time.Instant.parse("2026-11-18T22:00:00Z"), mismatch.backupCreatedAt());
            assertTrue(mismatch.derivedAt() != null);

            // a newer backup without a relation: the device is absent, never the older verdict
            insertBackup(db, "rd-manifest-3", device, "rd-ref-3", "2026-11-19T22:00:00Z");
            assertTrue(reader.latestPerDevice().stream().noneMatch(r -> r.deviceId().equals(device)));
        }
    }

    @Test
    void v253DropsThePositionalRowsSoNoBrokenVerdictIsShown() throws Exception {
        try (Connection db = fixture.migrateConnection()) {
            String device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            insertBackup(db, "manifest-id-9", device, "storage-ref-9", "2026-11-18T22:00:00Z");
            repository.record("storage-ref-9", device, "MISMATCH", 10, 9, 0, Map.of());
            assertTrue(repository.linkManifest("storage-ref-9", "manifest-id-9"));
            assertEquals("MISMATCH", serviceRelation(db, device));
            String sql = Files.readString(Ui2PostgresFixture.migrationDirectory().resolve("V253__asa_config_relation_manifest_key.sql"));
            try (var st = db.createStatement()) {
                st.execute(sql.substring(sql.indexOf("DELETE FROM asa_config_relation")));
            }
            assertNull(serviceRelation(db, device));
            try (var st = db.createStatement(); var rs = st.executeQuery("select count(*) from asa_config_relation")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
