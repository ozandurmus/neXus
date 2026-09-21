package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.BackupDisposition;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.DeleteResult;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;

class DeviceDeletionPersistenceTest {

    private static Ui2PostgresFixture fixture;
    private static JooqDeviceRepository repository;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("device_delete_backup_disposition");
        repository = new JooqDeviceRepository(new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    @Test
    void keepLeavesArtefactAndLedgerAfterDeviceDeletion() throws SQLException {
        Seed seed = seed(true);

        DeleteResult result = repository.deleteDevice(
                seed.deviceId(), BackupDisposition.KEEP, Ui2Rows.ACTOR, "device.delete");

        assertTrue(result.deleted());
        assertEquals(1, result.backupArtefactCount());
        assertCounts(seed, 0, 1, 1);
    }

    @Test
    void removeDeletesArtefactButLeavesLedgerAfterDeviceDeletion() throws SQLException {
        Seed seed = seed(true);

        DeleteResult result = repository.deleteDevice(
                seed.deviceId(), BackupDisposition.REMOVE, Ui2Rows.ACTOR, "device.delete");

        assertTrue(result.deleted());
        assertEquals(1, result.backupArtefactCount());
        assertCounts(seed, 0, 0, 1);
    }

    @Test
    void missingDispositionReportsExactCountAndChangesNothing() throws SQLException {
        Seed seed = seed(true);
        String secondArtefactId = insertArtefact(seed.deviceId());

        DeleteResult result = repository.deleteDevice(seed.deviceId(), null, Ui2Rows.ACTOR, "device.delete");

        assertFalse(result.deleted());
        assertTrue(result.dispositionRequired());
        assertEquals(2, result.backupArtefactCount());
        assertCounts(seed, 1, 2, 1);
        try (Connection app = fixture.appConnection()) {
            assertEquals(1, Ui2Rows.count(app,
                    "SELECT count(*) FROM artefact_retention_ledger WHERE artefact_id = '"
                            + secondArtefactId + "'"));
        }
    }

    @Test
    void noArtefactsNeedsNoDisposition() throws SQLException {
        Seed seed = seed(false);

        DeleteResult result = repository.deleteDevice(seed.deviceId(), null, Ui2Rows.ACTOR, "device.delete");

        assertTrue(result.deleted());
        assertFalse(result.dispositionRequired());
        assertEquals(0, result.backupArtefactCount());
        assertCounts(seed, 0, 0, 0);
    }

    private static Seed seed(boolean withArtefact) throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String credentialId = Ui2Rows.insertCredentialReference(app);
            String deviceId = Ui2Rows.insertDevice(app, credentialId, "ENROLLED");
            String artefactId = withArtefact ? insertArtefact(deviceId) : null;
            return new Seed(deviceId, artefactId);
        }
    }

    private static String insertArtefact(String deviceId) throws SQLException {
        String artefactId = Ui2Rows.opaqueId("artefact");
        String ledgerId = Ui2Rows.opaqueId("ledger");
        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (Statement statement = app.createStatement()) {
                statement.execute("INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, "
                        + "ciphertext_bytes, key_id, wrapped_data_key, validation, retention_tier, "
                        + "recovery_volume_path) VALUES ('" + artefactId + "', '" + deviceId
                        + "', 'backup', 'palo_alto', 'synthetic-fingerprint', 'plain-sha', 10, "
                        + "'cipher-sha', 20, 'key', decode('00', 'hex'), "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', '/app/artefact-store')");
                statement.execute("INSERT INTO artefact_retention_ledger(ledger_id, artefact_id, event, "
                        + "retention_tier) VALUES ('" + ledgerId + "', '" + artefactId
                        + "', 'created', 'standard')");
            }
            app.commit();
        }
        return artefactId;
    }

    private static void assertCounts(Seed seed, long devices, long artefacts, long ledgerRows) throws SQLException {
        try (Connection app = fixture.appConnection()) {
            assertEquals(devices, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + seed.deviceId() + "'"));
            assertEquals(artefacts, Ui2Rows.count(app,
                    "SELECT count(*) FROM backup_artefact WHERE device_id = '" + seed.deviceId() + "'"));
            if (seed.artefactId() != null) {
                assertEquals(ledgerRows, Ui2Rows.count(app,
                        "SELECT count(*) FROM artefact_retention_ledger WHERE artefact_id = '"
                                + seed.artefactId() + "'"));
            } else {
                assertEquals(0, ledgerRows);
            }
        }
    }

    private record Seed(String deviceId, String artefactId) {
    }
}
