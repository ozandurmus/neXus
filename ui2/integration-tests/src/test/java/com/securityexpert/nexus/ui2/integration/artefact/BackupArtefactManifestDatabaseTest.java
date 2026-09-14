package com.securityexpert.nexus.ui2.integration.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;

/**
 * Migration V17 (BK-16, BK-18), proved against a real PostgreSQL 16 server
 * -- the one invariant that cannot be proven with a mocked {@code
 * DSLContext} (see {@code JooqBackupArtefactManifestRepositoryTest} in
 * {@code persistence} for everything else): C7 section 8 criterion 6, the
 * retention ledger is append-only for {@code ui2_app} from its first row --
 * an {@code UPDATE} and a {@code DELETE} both fail, enforced by the
 * database's own {@code REVOKE}, not by application code choosing not to
 * issue them.
 *
 * <p>Excluded from this movement's own build command ({@code -x
 * :integration-tests:test}), per WORKER.md -- this test exists for the
 * later real-environment validation gate (AGENTS.md: "Automated validation
 * and real-environment validation are separate gates").</p>
 */
class BackupArtefactManifestDatabaseTest {

    private static Ui2PostgresFixture fixture;
    private static String deviceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("backup_artefact_manifest");
        try (Connection app = fixture.appConnection()) {
            String credentialReferenceId = Ui2Rows.insertCredentialReference(app);
            deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "ENROLLED");
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static String insertManifestRow(Connection app) throws SQLException {
        String artefactId = Ui2Rows.opaqueId("artefact");
        Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, Ui2Rows.ACTION);
        try (Statement statement = app.createStatement()) {
            statement.execute("INSERT INTO backup_artefact(artefact_id, device_id, artefact_class, vendor, "
                    + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, "
                    + "key_id, wrapped_data_key, validation, retention_tier, recovery_volume_path) VALUES ("
                    + "'" + artefactId + "', '" + deviceId + "', 'configuration', 'palo_alto', "
                    + "'" + "a".repeat(64) + "', 'plain-sha', 10, 'cipher-sha', 26, 'v1', decode('00', 'hex'), "
                    + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                    + "'standard', '/app/artefact-store')");
        }
        return artefactId;
    }

    @Test
    void theRetentionLedgerAcceptsInsertButRefusesUpdateAndDelete() throws SQLException {
        String artefactId;
        String ledgerId = Ui2Rows.opaqueId("ledger");
        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            artefactId = insertManifestRow(app);
            try (Statement statement = app.createStatement()) {
                statement.execute("INSERT INTO artefact_retention_ledger(ledger_id, artefact_id, event, "
                        + "retention_tier) VALUES ('" + ledgerId + "', '" + artefactId + "', 'created', 'standard')");
            }
            app.commit();
        }

        try (Connection app = fixture.appConnection()) {
            assertEquals(1L, Ui2Rows.count(app,
                    "SELECT count(*) FROM artefact_retention_ledger WHERE ledger_id = '" + ledgerId + "'"),
                    "the append-only INSERT itself must have succeeded");
        }

        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (Statement statement = app.createStatement()) {
                SQLException refused = assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE artefact_retention_ledger SET event = 'removed' WHERE ledger_id = '" + ledgerId
                                + "'"),
                        "BK-18: an UPDATE by the application role must be refused by the database, not merely "
                                + "unused by application code");
                assertEquals("42501", refused.getSQLState(), "expected PostgreSQL's insufficient_privilege state");
            }
            app.rollback();
        }

        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (Statement statement = app.createStatement()) {
                SQLException refused = assertThrows(SQLException.class,
                        () -> statement.execute("DELETE FROM artefact_retention_ledger WHERE ledger_id = '"
                                + ledgerId + "'"),
                        "BK-18: a DELETE by the application role must be refused by the database");
                assertEquals("42501", refused.getSQLState(), "expected PostgreSQL's insufficient_privilege state");
            }
            app.rollback();
        }

        try (Connection app = fixture.appConnection()) {
            assertEquals(1L, Ui2Rows.count(app,
                    "SELECT count(*) FROM artefact_retention_ledger WHERE ledger_id = '" + ledgerId + "'"),
                    "the row must be exactly as the single INSERT left it");
        }
    }
}
