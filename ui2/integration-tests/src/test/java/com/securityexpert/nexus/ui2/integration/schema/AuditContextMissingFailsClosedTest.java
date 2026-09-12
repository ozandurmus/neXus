package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §7 test 4 / C1 §3.5 "Test-enforced" first case /
 * {@code UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md} §4's
 * {@code AuditContextIntegrationTest} spec, proved against a real
 * PostgreSQL 16 server: a raw {@code ui2_app} mutation without
 * {@code SET LOCAL app.actor_fingerprint}/{@code app.action_id} fails with
 * {@code audit_context_missing}, <b>and</b> a follow-up {@code SELECT}
 * proves no row was written — the mutation never committed, not merely that
 * an exception was thrown.
 */
class AuditContextMissingFailsClosedTest {

    private static Ui2PostgresFixture fixture;
    private static String credentialReferenceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_context_missing");
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
    void rawMutationWithoutAuditContextRaisesAuditContextMissingAndWritesNoRow() throws SQLException {
        String deviceId = Ui2Rows.opaqueId("dev-no-context");

        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            try (Statement statement = app.createStatement()) {
                SQLException failure = assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO devices(device_id, vendor_hint, registration_source, is_test_target, "
                                + "enrollment_state, disabled, credential_reference_id) VALUES ('"
                                + deviceId + "', 'harness-vendor', 'manual', true, 'DRAFT', false, '"
                                + credentialReferenceId + "')"));
                assertTrue(failure.getMessage().contains("audit_context_missing"),
                        "fn_audit_capture() must refuse a mutation with no audit context, naming "
                                + "audit_context_missing");
                // The raised exception names the table and its primary-key
                // column, so an operator can see which mutation was refused.
                assertTrue(failure.getMessage().contains("devices.device_id"),
                        "the refusal must name the target table and primary-key column");
            }
            app.rollback();
            app.setAutoCommit(true);
        }

        // A separate, fresh connection: the mutation must be absent because it
        // never committed, not because this transaction can no longer see it.
        try (Connection app = fixture.appConnection()) {
            assertEquals(0L, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + deviceId + "'"),
                    "the refused INSERT must have written no devices row");
            assertEquals(0L, Ui2Rows.countAuditRows(app, "devices", deviceId),
                    "the refused INSERT must have written no audit_log row either");
        }
    }

    @Test
    void oneHalfOfTheAuditContextIsNotEnough() throws SQLException {
        // C1 §3.5 requires both variables. Setting only the actor must fail
        // exactly as setting neither does -- a partially-populated context is
        // not a context (AGENTS.md fail-closed law).
        String deviceId = Ui2Rows.opaqueId("dev-half-context");

        try (Connection app = fixture.appConnection()) {
            app.setAutoCommit(false);
            try (Statement statement = app.createStatement()) {
                statement.execute("SET LOCAL app.actor_fingerprint = 'harness-actor-fingerprint'");
                SQLException failure = assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO devices(device_id, vendor_hint, registration_source, is_test_target, "
                                + "enrollment_state, disabled, credential_reference_id) VALUES ('"
                                + deviceId + "', 'harness-vendor', 'manual', true, 'DRAFT', false, '"
                                + credentialReferenceId + "')"));
                assertTrue(failure.getMessage().contains("audit_context_missing"));
            }
            app.rollback();
            app.setAutoCommit(true);
        }

        try (Connection app = fixture.appConnection()) {
            assertEquals(0L, Ui2Rows.count(app,
                    "SELECT count(*) FROM devices WHERE device_id = '" + deviceId + "'"));
        }
    }
}
