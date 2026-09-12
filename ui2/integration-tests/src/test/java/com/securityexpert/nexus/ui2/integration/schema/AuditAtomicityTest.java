package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §7 test 5 / C1 §3.5 "Test-enforced" third case, proved against a
 * real PostgreSQL 16 server: with both session variables set, a mutation
 * that is rolled back leaves zero matching {@code audit_log} rows. The
 * mutation and its audit row commit together or neither does — they are not
 * merely "usually true together".
 */
class AuditAtomicityTest {

    private static Ui2PostgresFixture fixture;
    private static String credentialReferenceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_atomicity");
        try (Connection app = fixture.appConnection()) {
            credentialReferenceId = Ui2Rows.insertCredentialReference(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void rolledBackMutationLeavesNoAuditLogRow() throws SQLException {
        String deviceId = Ui2Rows.opaqueId("dev-rollback");

        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, "harness.rollback");
            try (Statement statement = app.createStatement()) {
                statement.execute("INSERT INTO devices(device_id, vendor_hint, registration_source, "
                        + "is_test_target, enrollment_state, disabled, credential_reference_id) VALUES ('"
                        + deviceId + "', 'harness-vendor', 'manual', true, 'DRAFT', false, '"
                        + credentialReferenceId + "')");
            }
            // Inside the transaction the pair is already both there -- the
            // audit row is written by the same statement's AFTER trigger, so
            // "rolled back together" is a real claim about this pair, not
            // about a row that never existed.
            assertEquals(1L, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + deviceId + "'"));
            assertEquals(1L, Ui2Rows.countAuditRows(app, "devices", deviceId));

            app.rollback();
            app.setAutoCommit(true);
        }

        try (Connection app = fixture.appConnection()) {
            assertEquals(0L, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + deviceId + "'"),
                    "the rolled-back mutation must leave no devices row");
            assertEquals(0L, Ui2Rows.countAuditRows(app, "devices", deviceId),
                    "the rolled-back mutation must leave no audit_log row");
        }
    }

    @Test
    void committedMutationLeavesExactlyOneAuditLogRowCarryingTheContext() throws SQLException {
        String deviceId;
        try (Connection app = fixture.appConnection()) {
            deviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "DRAFT");
        }

        try (Connection app = fixture.appConnection();
                Statement statement = app.createStatement();
                var rows = statement.executeQuery(
                        "SELECT operation, actor_fingerprint, action_id, before_state, after_state "
                                + "FROM audit_log WHERE table_name = 'devices' AND row_pk = '" + deviceId + "'")) {
            assertEquals(true, rows.next(), "the committed mutation must leave an audit_log row");
            assertEquals("INSERT", rows.getString("operation"));
            assertEquals(Ui2Rows.ACTOR, rows.getString("actor_fingerprint"));
            assertEquals(Ui2Rows.ACTION, rows.getString("action_id"));
            assertEquals(null, rows.getString("before_state"), "an INSERT has no before_state");
            assertEquals(false, rows.getString("after_state") == null, "an INSERT must carry an after_state");
            assertEquals(false, rows.next(), "exactly one audit_log row, never two");
        }
    }
}
