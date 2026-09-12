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
 * Contract §7 test 7 / C1 §9 AC-6 check 5, proved against a real PostgreSQL
 * 16 server: as {@code ui2_app} — with the connection opened outside the
 * assertion block — a raw {@code INSERT} on {@code audit_log} fails with
 * SQLState {@code 42501}. Only {@code fn_audit_capture()}'s
 * {@code SECURITY DEFINER} execution may ever write that table.
 */
class DirectAuditLogWriteDeniedTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("direct_audit_write");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void ui2AppRawInsertOnAuditLogFailsWithSqlState42501() throws SQLException {
        try (Connection app = fixture.appConnection(); Statement statement = app.createStatement()) {
            SQLException denied = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id) "
                            + "VALUES ('devices', 'forged', 'INSERT', 'forged-actor', 'forged.action')"));
            assertEquals("42501", denied.getSQLState());
        }
    }

    @Test
    void ui2AppCanStillReadAuditLogAndCannotUpdateOrDeleteIt() throws SQLException {
        // V1 grants SELECT and revokes INSERT/UPDATE/DELETE. Read access is
        // part of the same grant decision, so both halves are asserted here
        // rather than assumed from the INSERT denial above.
        String credentialReferenceId;
        try (Connection app = fixture.appConnection()) {
            credentialReferenceId = Ui2Rows.insertCredentialReference(app);
        }

        try (Connection app = fixture.appConnection()) {
            assertTrue(Ui2Rows.countAuditRows(app, "credential_references", credentialReferenceId) == 1L,
                    "ui2_app must be able to read audit_log");

            try (Statement statement = app.createStatement()) {
                SQLException updateDenied = assertThrows(SQLException.class,
                        () -> statement.execute("UPDATE audit_log SET actor_fingerprint = 'forged'"));
                assertEquals("42501", updateDenied.getSQLState());
            }
            try (Statement statement = app.createStatement()) {
                SQLException deleteDenied = assertThrows(SQLException.class,
                        () -> statement.execute("DELETE FROM audit_log"));
                assertEquals("42501", deleteDenied.getSQLState());
            }
        }
    }
}
